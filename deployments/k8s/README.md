# Kubernetes deployment

Manifests are organised as a flat Kustomize base (numerically prefixed for human-readable
apply order) so any cluster can install everything with a single `kubectl apply -k`.

```
deployments/k8s/
├── kustomization.yaml         entry point — namespace, common labels, image tags
├── 00-namespace.yaml
├── 01-secret.yaml             db + redis credentials (replace for prod!)
├── 02-configmap.yaml          MYSQL_*, REDIS_*, KAFKA_BOOTSTRAP_SERVERS, KAFKA_TOPIC, ...
├── 10-mysql-config.yaml       my.cnf with binlog ROW + GTID, schema init SQL
├── 11-mysql.yaml              StatefulSet + headless Service + 5 Gi PVC
├── 15-kafka.yaml              KRaft single-broker StatefulSet + headless Service
├── 16-kafka-connect.yaml      debezium/connect StatefulSet + ClusterIP Service :8083
├── 17-debezium-connector.yaml ConfigMap (connector JSON) + one-shot Job that PUTs it
├── 20-redis.yaml              StatefulSet + Service + 1 Gi PVC, AOF + LRU
├── 30-writer.yaml             Deployment(2) + ClusterIP, init-container waits for MySQL
├── 31-reader.yaml             Deployment(3) + ClusterIP, waits for MySQL + Redis
├── 32-invalidator.yaml        Deployment(3) — Kafka consumer, scales by partition count
├── 40-hpa.yaml                HPAs for writer + reader (CPU based)
├── 99-ingress.yaml            nginx-ingress, host-based: writer / reader .cache.local
└── kind-cluster.yaml          local kind config with :80/:443 mapped through
```

## Data flow

```
client ──HTTP──► writer ──INSERT/UPDATE──► MySQL ──binlog──► Kafka Connect (Debezium)
                                                                    │
                                                                    ▼
                                                  Kafka topic: cdc.catalog.items
                                                                    │
                                                                    ▼
                                  invalidator pods (Kafka consumer group: cache-invalidator)
                                                                    │
                                                                    ▼ DEL v1:item:{id}
                                                                  Redis
                                                                    ▲
client ──HTTP──► reader ──cache-aside (GET, then SETEX on miss)─────┘
                          └──fallback──► MySQL on cache miss
```

## Quickstart on local kind

```bash
# 1. cluster
kind create cluster --config deployments/k8s/kind-cluster.yaml

# 2. nginx-ingress (matches the ingressClassName in 99-ingress.yaml)
kubectl apply -f https://raw.githubusercontent.com/kubernetes/ingress-nginx/main/deploy/static/provider/kind/deploy.yaml
kubectl wait --namespace ingress-nginx \
  --for=condition=ready pod \
  --selector=app.kubernetes.io/component=controller \
  --timeout=120s

# 3. metrics-server (so the HPAs can read CPU)
kubectl apply -f https://github.com/kubernetes-sigs/metrics-server/releases/latest/download/components.yaml
kubectl patch deploy metrics-server -n kube-system --type=json \
  -p='[{"op":"add","path":"/spec/template/spec/containers/0/args/-","value":"--kubelet-insecure-tls"}]'

# 4. build the service images and side-load them into the kind cluster
make k8s-images
make k8s-load

# 5. apply everything
make k8s-up

# 6. point /etc/hosts at the kind ingress
echo "127.0.0.1 writer.cache.local reader.cache.local" | sudo tee -a /etc/hosts

# 7. exercise the system end-to-end
curl -s -X POST http://writer.cache.local/api/v1/items \
  -H 'Content-Type: application/json' \
  -d '{"name":"widget","description":"shiny","price_cents":1999,"currency":"USD","stock":7}'

curl -s http://reader.cache.local/api/v1/items/1 -i | grep -i x-cache  # MISS first
curl -s http://reader.cache.local/api/v1/items/1 -i | grep -i x-cache  # HIT after caching

# 8. trigger an invalidation
curl -s -X PUT http://writer.cache.local/api/v1/items/1 \
  -H 'Content-Type: application/json' \
  -d '{"name":"widget","description":"updated","price_cents":2999,"currency":"USD","stock":7}'

# Within ~50–100 ms the binlog event has flowed MySQL → Kafka → invalidator → DEL.
sleep 1
curl -s http://reader.cache.local/api/v1/items/1 -i | grep -i x-cache  # MISS again
```

## Capacity & resource notes

| Component     | Replicas       | Requests (per pod) | Limits         | PVC    |
| ------------- | -------------- | ------------------ | -------------- | ------ |
| mysql         | 1 (StatefulSet)| 250m / 512Mi       | 1 / 1Gi        | 5 Gi   |
| kafka         | 1 (StatefulSet)| 300m / 768Mi       | 1.5 / 1.5Gi    | 5 Gi   |
| kafka-connect | 1 (StatefulSet)| 300m / 768Mi       | 1.5 / 1.5Gi    | 1 Gi   |
| redis         | 1              | 100m / 128Mi       | 500m / 320Mi   | 1 Gi   |
| writer        | 2 → HPA 6      | 150m / 320Mi       | 1 / 768Mi      | —      |
| reader        | 3 → HPA 10     | 200m / 320Mi       | 1 / 768Mi      | —      |
| invalidator   | 3 (Deployment) | 150m / 320Mi       | 1 / 768Mi      | —      |

Total at idle: ~2 CPU / ~3.5 GiB requested. Fits inside a 3-node kind cluster on a 16 GB
laptop with room to spare for the HPAs to fire under load.

## Why the invalidator is a Deployment, not a StatefulSet

In the Kafka-based design the consumer is **stateless w.r.t. the binlog** — Kafka's
`__consumer_offsets` topic remembers where each consumer group left off, so we don't need
to persist anything per pod. The `cdc.catalog.items` topic is created with 6 partitions
(see 17-debezium-connector.yaml), so up to 6 invalidator replicas can process events in
parallel. Beyond 6, additional replicas just sit idle waiting for a rebalance — bump
`topic.creation.default.partitions` first if you want to scale further.

## Production notes

- Replace `01-secret.yaml` with SealedSecrets, External Secrets, or a managed KMS.
- Run a dedicated `cdc` MySQL user (REPLICATION SLAVE/CLIENT + SELECT only) and plumb it
  into the connector via `config.providers=file` in Kafka Connect.
- Add resource quotas + LimitRange in the `caching-infra` namespace.
- Add NetworkPolicies — only kafka-connect should reach mysql:3306 over the binlog port;
  only writer/reader should reach mysql; only reader/invalidator should reach redis.
- Add PodDisruptionBudgets for writer / reader / invalidator so a node drain can't take
  every replica out at once.
- For HA Kafka: 3-node KRaft cluster, increase `*_REPLICATION_FACTOR` to 3, and run >=2
  Kafka Connect workers.
- Front the Ingress with cert-manager + a `letsencrypt-prod` ClusterIssuer for HTTPS.
