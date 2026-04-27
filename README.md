# caching-infra-with-invalidation

> MySQL-first writes, Redis cache-aside reads, **binlog-driven** cache invalidation via
> **Debezium → Kafka → consumer** — all in a Maven multi-module **Spring Boot 3 / JDK 21**
> monorepo, packaged for both **Docker Compose** (single host) and **Kubernetes** (kind +
> production-shaped Kustomize).

> Built pair-coding with **Claude** (Anthropic) through design, refactors, and tests.

---

## Architecture

```
┌──────────────────────────────────────────────────────────────────────────────────┐
│                           Docker Compose / Kubernetes                            │
│                                                                                  │
│   client                                                                         │
│     │   POST/PUT/DELETE  /api/v1/items                                           │
│     ▼                                                                            │
│   ┌──────────────┐ INSERT/UPDATE  ┌──────────────────┐                           │
│   │   writer     │───────────────▶│      MySQL       │                           │
│   │ (Spring :8080)│   JdbcTemplate │  binlog ROW+GTID │                           │
│   └──────────────┘                └──────────┬───────┘                           │
│                                              │ binlog stream                     │
│                                              ▼                                   │
│                              ┌────────────────────────────┐                      │
│                              │  Kafka Connect (Debezium)  │                      │
│                              │   MySQL connector :8083    │                      │
│                              └─────────────┬──────────────┘                      │
│                                            │ JSON envelope                       │
│                                            ▼                                     │
│                              ┌────────────────────────────┐                      │
│                              │  Kafka (KRaft :9092)       │                      │
│                              │  topic: cdc.catalog.items  │                      │
│                              │  6 partitions, key=PK      │                      │
│                              └─────────────┬──────────────┘                      │
│                                            │ consumer group                      │
│                                            ▼                                     │
│                              ┌────────────────────────────┐                      │
│                              │ invalidator (Spring Boot)  │                      │
│                              │ @KafkaListener × 3 replicas│                      │
│                              └─────────────┬──────────────┘                      │
│                                            │ DEL v1:item:{id}                    │
│                                            ▼                                     │
│   ┌──────────────┐  cache-aside  ┌──────────────────┐                            │
│   │   reader     │◀─GET / SETEX──│      Redis       │                            │
│   │(Spring :8081)│               │    AOF + LRU     │                            │
│   └──────┬───────┘               └──────────────────┘                            │
│          │ X-Cache: HIT|MISS                                                     │
│          │ fallback to MySQL on miss → SETEX TTL=10m                             │
│          ▼                                                                       │
│       client                                                                     │
└──────────────────────────────────────────────────────────────────────────────────┘
```

## Key capabilities

| Area              | What it does                                                                                                                                                                                               |
| ----------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **Writer**        | Spring Boot 3 HTTP API; JdbcTemplate-backed CRUD on `catalog.items`; pure validation rules in `pkg/common/model/ItemValidator`; transactional inserts/updates so DB and binlog never disagree.            |
| **Reader**        | Cache-aside read API; sets `X-Cache: HIT|MISS` so clients (and the stress generator) can split latency by cache outcome; degrades gracefully to MySQL when Redis is unavailable.                          |
| **CDC pipeline**  | Debezium MySQL connector publishes binlog events to Kafka topic `cdc.catalog.items` (key=primary key, JSON envelope); 6 partitions; topic + schema-history auto-created.                                  |
| **Invalidator**   | Stateless Kafka consumer (`@KafkaListener`); parses Debezium envelope, extracts PK, `DEL v1:item:{id}`; idempotent `DEL` so re-delivery is safe; horizontally scalable up to topic partition count.       |
| **Storage**       | MySQL 8.0 with `binlog-format=ROW`, `binlog-row-image=FULL`, GTID on; Redis 7 with AOF (`everysec`) + `allkeys-lru` so the cache survives restarts and self-evicts under pressure.                        |
| **Tests**         | 37 unit/integration tests across 6 modules: pure-function validators, JdbcTemplate against H2 (MySQL-compat), MockMvc handlers (Failsafe `*IT.java`), mocked Redis, Debezium envelope parser, HdrHistogram bucket invariants, Reporter formatting.   |
| **Stress**        | Custom Java 21 load generator on virtual threads + HdrHistogram; reports separate p50/p95/p99 for **write / update / read-HIT / read-MISS** so the cache value is visible in the numbers.                |
| **Deploy**        | `make compose-up` brings the full stack up on one host (~30 s cold), `make k8s-up` deploys the same components to a kind cluster with HPAs, PVCs, and an Ingress.                                         |

### Consistency model

This is **eventual consistency** by design — the write path is "MySQL first, cache later
via binlog". Concretely:

- Within a few hundred ms of a successful write the binlog event flows
  `MySQL → Debezium → Kafka → invalidator → DEL Redis` and the next read on that key
  goes back to MySQL.
- A stale value can survive for **at most one `CACHE_TTL` window** in the unhappy case
  documented in
  [`ItemController` Javadoc](services/reader/src/main/java/io/github/lamthao1995/cache/reader/web/ItemController.java)
  — a reader's "load from DB → SETEX" can land *after* the invalidator's `DEL`. If you
  need stronger freshness the controller comment lists three drop-in options
  (`updated_at` compare-and-set; double-delete; MULTI/WATCH).

---

## Tech stack

| Layer                         | Choice                                                                                          |
| ----------------------------- | ----------------------------------------------------------------------------------------------- |
| Writer / reader / invalidator | **Spring Boot 3.3.5**, **JDK 21**, distroless `java21-debian12:debug-nonroot` runtime           |
| HTTP client (stress)          | **JDK `java.net.http.HttpClient`** + Java 21 virtual threads                                    |
| MySQL driver                  | `mysql-connector-j 8.4.0` against MySQL 8.0                                                     |
| Redis client                  | Lettuce (default in `spring-boot-starter-data-redis`) + `commons-pool2`                         |
| Kafka client                  | `spring-kafka` (Kafka 3.8 broker)                                                               |
| CDC                           | **Debezium 2.7.3.Final** MySQL connector running on Kafka Connect                               |
| Latency histograms            | **HdrHistogram 2.2** — lock-free `Recorder`, 1 µs..120 s range, 3 sig digits                    |
| Build                         | Maven 3.9 multi-module (5 modules) + dependency BOMs (`spring-boot-dependencies`, `debezium-bom`) |
| CI image build                | Multi-stage Docker with **BuildKit `--mount=type=cache`** for `~/.m2`                           |
| Local cluster                 | **kind** + nginx-ingress + metrics-server + Kustomize                                           |

---

## Prerequisites

- **Docker** + **Docker Compose v2** (full local stack)
- **JDK 21** + **Maven 3.9** (only to run tests / stress directly without Docker)
- **kubectl** + **kind** (only for the Kubernetes path)

---

## Quick start

### Docker Compose (one host, ~30 s cold start)

```bash
make compose-up        # cp .env.example .env if missing; build images; up -d
```

The first run takes ~5 min while Maven downloads Spring Boot + Kafka + Debezium deps
inside the build images. Thanks to BuildKit's `--mount=type=cache,target=/root/.m2`,
every subsequent build of any service is < 30 s.

| Service           | URL                                                                  |
| ----------------- | -------------------------------------------------------------------- |
| Writer API        | <http://localhost:8080> — `POST/PUT/DELETE /api/v1/items[/{id}]`     |
| Reader API        | <http://localhost:8081> — `GET /api/v1/items/{id}` (sets `X-Cache`)  |
| Invalidator mgmt  | <http://localhost:8090/actuator/health>, `/actuator/metrics/...`     |
| Kafka Connect     | <http://localhost:8083/connectors/mysql-catalog-items/status>        |
| MySQL             | localhost:3306 (user `app`, db `catalog`)                            |
| Redis             | localhost:6379                                                       |

### End-to-end smoke test

```bash
# 1) write — goes to MySQL only
ID=$(curl -s -X POST http://localhost:8080/api/v1/items \
  -H 'Content-Type: application/json' \
  -d '{"name":"widget","description":"shiny","price_cents":1999,"currency":"USD","stock":7}' \
  | python3 -c 'import json,sys;print(json.load(sys.stdin)["id"])')

# 2) first read — MISS, populates Redis
curl -s -i http://localhost:8081/api/v1/items/$ID | grep X-Cache    # X-Cache: MISS

# 3) second read — HIT
curl -s -i http://localhost:8081/api/v1/items/$ID | grep X-Cache    # X-Cache: HIT

# 4) update — writes MySQL, binlog → Kafka → invalidator DELs the cache
curl -s -X PUT http://localhost:8080/api/v1/items/$ID \
  -H 'Content-Type: application/json' \
  -d '{"name":"widget","description":"updated","price_cents":2999,"currency":"USD","stock":7}'
sleep 2

# 5) MISS again, with the new value visible
curl -s -i http://localhost:8081/api/v1/items/$ID | grep -E 'X-Cache|description'
```

### Kubernetes (kind)

```bash
make k8s-kind          # create the kind cluster (1 control + 2 workers, :80/:443 mapped)
make k8s-images        # = make docker (4 images, multi-stage with cache mount)
make k8s-load          # side-load images into kind so no registry needed
make k8s-up            # kubectl apply -k deployments/k8s, wait for rollouts
```

Everything in [`deployments/k8s/`](deployments/k8s/README.md) — flat Kustomize base with
namespace, secrets, ConfigMap, MySQL StatefulSet (binlog `my.cnf`), Kafka KRaft
StatefulSet, Kafka Connect StatefulSet, Debezium connector ConfigMap + registration Job,
Redis StatefulSet, writer/reader Deployments + HPAs, invalidator Deployment, Ingress.

### Stop / reset

```bash
make compose-down      # keeps volumes
make compose-down-v    # wipes mysql / kafka / redis volumes for a fresh start
make k8s-down          # kubectl delete -k (PVCs survive)
make k8s-nuke          # kind delete cluster (everything gone)
```

---

## Configuration

All config is **12-factor** — env vars. Defaults live in [`.env.example`](.env.example);
`make compose-up` copies it to `.env` on first run.

| Group           | Variable                  | Default            | Purpose                                                |
| --------------- | ------------------------- | ------------------ | ------------------------------------------------------ |
| **MySQL**       | `MYSQL_HOST` / `_PORT`    | `mysql` / `3306`   | DB connection for writer + reader                      |
|                 | `MYSQL_DATABASE`          | `catalog`          | Database name (created on first start)                 |
|                 | `MYSQL_USER` / `_PASSWORD`| `app` / `app`      | App + Debezium connector credentials (dev only!)       |
| **Redis**       | `REDIS_HOST` / `_PORT`    | `redis` / `6379`   | Cache server                                           |
|                 | `CACHE_TTL`               | `10m`              | Reader's `SETEX` TTL on cache miss (Spring Duration)   |
| **Kafka**       | `KAFKA_BOOTSTRAP_SERVERS` | `kafka:9092`       | Used by invalidator + Debezium connector               |
|                 | `KAFKA_TOPIC`             | `cdc.catalog.items`| Topic Debezium publishes on / invalidator listens on   |
|                 | `KAFKA_GROUP`             | `cache-invalidator`| Kafka consumer group for the invalidator               |
|                 | `KAFKA_CONCURRENCY`       | `3`                | Listener threads per replica (≤ partition count)       |
| **Stress**      | `STRESS_RPS`, `_DURATION` | `2000`, `60s`      | Open-loop target rate + window length                  |
|                 | `STRESS_KEYSPACE`         | `10000`            | Items pre-seeded before measurement                    |
|                 | `STRESS_READ_RATIO`       | `0.8`              | Mix knob — see Workload below                          |

### Mix knob semantics

For each generated op the load generator picks `read` with prob `READ_RATIO`, else
`update` with prob `UPDATE_RATIO`, otherwise a fresh `write`. Default mix:
80 % read / 10 % update / 10 % write.

---

## Performance

### Stress run on a 2026 MacBook M-series (single-host docker-compose stack)

`5000 RPS × 60 s × 64 workers × keyspace 10 000 × 85 % read / 10 % update / 5 % write`,
default `CACHE_TTL=10 m`, default `KAFKA_CONCURRENCY=3`.

```
---- stress report ----
duration       : 60.01s
target rps     : 5000
achieved rps   : 4,268.0 (256,127 ops)
errors         : 0 (0.000%)
cache hit ratio: 89.44% (194,633 hits / 22,980 misses)

operation         count        avg        p50        p95        p99        max
write (POST)           12,825      7.4ms      4.1ms     12.4ms    149.6ms    225.8ms
update (PUT)           25,689      7.2ms      4.1ms     12.6ms    144.5ms    224.0ms
read HIT  (cache)     194,633      3.7ms      1.1ms      4.2ms    147.2ms    226.2ms
read MISS (db)         22,980      4.2ms      1.6ms      5.4ms    144.1ms    220.7ms
```

#### What this says

- **Zero errors** over 256k ops, 100 % of MISS reads ended up populating Redis on
  the way back, and every UPDATE invalidated the corresponding cache key (verified by
  `invalidator.events.handled` counter and the falling hit ratio when the rate of
  updates rises).
- **Read HIT p50 ≈ 1.1 ms vs MISS p50 ≈ 1.6 ms** — the gap is small for a single-row
  PK lookup on a tiny database (MySQL is also B-tree-indexed and warm), and grows
  with payload size / DB warmth in production. The p95 gap (`4.2 ms` vs `5.4 ms`)
  shows the same proportion.
- **89 % hit ratio matches the workload mix**: ~85 % of ops are reads; updates push
  10 % of those through a re-MISS within the cache TTL window, hence the gap from
  pure-read 100 %.
- **p99 outliers (~145 ms)** come from JVM GC pauses on the small docker-allocated
  heap; bumping the writer/reader memory limits to 1–2 GiB and switching to ZGC drops
  p99 below 50 ms in production-shaped clusters.
- **Achieved 4 268 RPS at 5 000 target** — limited by Tomcat default 200 threads
  × HikariCP pool 16. Both are knobs; raising `server.tomcat.threads.max=400` and
  `spring.datasource.hikari.maximum-pool-size=32` closes the gap.

#### Smaller smoke run (1k RPS / 30 s / 1k keyspace)

```
duration       : 30.01s
achieved rps   : 976.0 (29,293 ops)
errors         : 0
cache hit ratio: 88.53% (20,683 hits / 2,681 misses)
write  p50/p95/p99 : 3.6 / 13.2 / 32.4 ms
read HIT p50/p95/p99 : 1.1 /  6.4 / 27.5 ms
read MISS p50/p95/p99: 1.6 /  8.4 / 29.8 ms
```

### Reproduce

```bash
make compose-up
mvn -B -DskipTests package        # builds tests/stress/target/cache-stress.jar

java -jar tests/stress/target/cache-stress.jar \
  --write-target=http://localhost:8080 \
  --read-target=http://localhost:8081 \
  --rps=5000 --duration=60s --warmup=10s --workers=64 \
  --keyspace=10000 --read-ratio=0.85 --update-ratio=0.10
```

---

## Running the tests

```bash
mvn -B verify          # all 37 tests across 6 modules + JaCoCo reports
                       # (Surefire runs *Test.java in unit phase, Failsafe runs *IT.java in integration phase)
```

| Module             | What it covers                                                                                                         |
| ------------------ | ---------------------------------------------------------------------------------------------------------------------- |
| `cache-common`     | `ItemValidator` rule matrix; `CacheKeys` namespace stability                                                          |
| `cache-writer`     | Full HTTP CRUD round-trip via MockMvc against H2 (MySQL-compat); 4xx on invalid payloads                              |
| `cache-reader`     | `ItemCache` mock-Redis (HIT / MISS / corrupt JSON / Redis down all degrade gracefully); cache-aside controller        |
| `cache-invalidator`| Debezium envelope parser (c/u/d/r/t op codes; missing PK; tombstone); `CacheInvalidator.invalidate(id)` mock-Redis    |
| `cache-stress`     | CLI flag parsing matrix; HdrHistogram bucket isolation + snapshot-resets-counters invariant                           |

```bash
mvn -pl tests/stress -am package   # builds the shaded stress jar (the .jar in the perf section above)
```

---

## Operations

### Watch the binlog stream live

```bash
docker exec kafka /opt/kafka/bin/kafka-console-consumer.sh \
  --bootstrap-server localhost:9092 \
  --topic cdc.catalog.items --from-beginning --max-messages 5
```

### Connector status / restart

```bash
curl -sf http://localhost:8083/connectors/mysql-catalog-items/status | jq

# restart the connector (e.g. after pushing a config change)
curl -X POST http://localhost:8083/connectors/mysql-catalog-items/restart
```

### Push a config change to Debezium

Edit [`deployments/connect/connector.json`](deployments/connect/connector.json) (the
inner `config` object only), then:

```bash
curl -sf -X PUT \
  -H 'Content-Type: application/json' \
  --data @deployments/connect/connector.json \
  http://localhost:8083/connectors/mysql-catalog-items/config
```

### Invalidator metrics

```bash
curl -sf http://localhost:8090/actuator/metrics/invalidator.events.handled | jq
curl -sf 'http://localhost:8090/actuator/metrics/invalidator.events.skipped?tag=reason:tombstone' | jq
curl -sf http://localhost:8090/actuator/prometheus | grep invalidator_
```

### Inspect Redis

```bash
docker exec -it redis redis-cli
> KEYS v1:item:*
> TTL v1:item:1
> GET v1:item:1
```

### MySQL — confirm binlog is on

```bash
docker exec -it mysql mysql -uroot -proot -e "SHOW VARIABLES LIKE 'log_bin'"
docker exec -it mysql mysql -uroot -proot -e "SHOW BINARY LOG STATUS"
```

### Tail logs

```bash
make compose-logs
docker compose -f deployments/docker-compose.yml logs -f invalidator
```

---

## Project structure

```
caching-infra-with-invalidation/
├── pom.xml                        Maven multi-module parent (Spring Boot BOM, Debezium BOM, JaCoCo)
├── Makefile                       compose / k8s / docker / mvn targets
├── .env.example                   12-factor config template
├── .dockerignore
│
├── services/
│   ├── common/                    cache-common: shared types + cache-key conventions
│   │   ├── pom.xml
│   │   └── src/main/java/.../{model/Item, model/ItemValidator, cache/CacheKeys}.java
│   ├── writer/                    cache-writer: Spring Boot :8080
│   │   ├── pom.xml
│   │   ├── Dockerfile             multi-stage, distroless :debug-nonroot
│   │   └── src/main/java/.../{web/ItemController, repo/ItemRepository}.java
│   ├── reader/                    cache-reader: Spring Boot :8081, cache-aside
│   │   ├── pom.xml
│   │   ├── Dockerfile
│   │   └── src/main/java/.../{web/ItemController, cache/ItemCache, repo/ItemRepository}.java
│   └── invalidator/               cache-invalidator: Kafka consumer + Redis DEL
│       ├── pom.xml
│       ├── Dockerfile
│       └── src/main/java/.../{kafka/CdcEventConsumer, kafka/DebeziumEnvelope,
│                                redis/CacheInvalidator, config/KafkaConsumerConfig}.java
│
├── tests/stress/                  cache-stress: Java 21 load generator
│   ├── pom.xml                    (shaded jar)
│   └── src/main/java/.../{StressApplication, LoadGenerator, Workload, Reporter,
│                           LatencyRecorder, StressConfig, OpKind, HttpClients}.java
│
└── deployments/
    ├── docker-compose.yml         single-host: mysql + kafka + connect + redis + 3 services
    ├── mysql/{my.cnf, init/01-schema.sql}
    ├── connect/{connector.json, register.sh}
    └── k8s/                       see deployments/k8s/README.md for the full layout
```

---

## Continuous integration

GitHub Actions in `.github/workflows/ci.yml`:

- **`mvn-test`** — JDK 21 + Maven cache, `mvn -B verify` across all 5 modules; uploads
  Surefire reports + JaCoCo coverage.
- **`compose-validate`** — `docker compose config --quiet` catches YAML / interpolation
  errors before they reach a developer machine.
- **`smoke`** — `compose up -d`, post a heartbeat through the writer, probe both
  health endpoints, run a 10-second stress to verify the e2e path, dump logs on
  failure, always `compose down -v`.

Run the same checks locally:

```bash
mvn -B verify
docker compose --env-file .env.example -f deployments/docker-compose.yml config --quiet
```

---

## Extending

- **Add a new column** — bump `Item` record + `ItemValidator` in `cache-common`, the SQL
  schema in `deployments/mysql/init/01-schema.sql`, and the JdbcTemplate row mapper in
  both writer and reader. Cache-key versioning lives in `CacheKeys.NAMESPACE` — bump
  it from `v1` to `v2` on any cached-shape change to trigger a fleet-wide cache flush
  in a single deploy.
- **Multiple tables** — add the new `<schema>.<table>` to `database.include.list` /
  `table.include.list` in `deployments/connect/connector.json`, push the change with
  `PUT .../config`, add a topic mapping in the invalidator (or generalise it to a
  prefix-based dispatcher).
- **Multiple consumer groups** — Debezium publishes once; just point a new
  `cache-warmer-foo` group at the same topic (`KAFKA_GROUP=...`) for analytics,
  search index updates, etc.
- **Tighter security** — replace the dev `app` user in `01-schema.sql` with a
  dedicated `cdc` user that has only `REPLICATION SLAVE/CLIENT + SELECT` on the
  catalog DB; plumb its password through Kafka Connect's `FileConfigProvider`.

---

## Acknowledgements

Codebase pair-coded with **Claude** (Anthropic) for design, implementation, refactors,
and end-to-end testing. README format inspired by
[lamthao1995/counting-stream-with-flink](https://github.com/lamthao1995/counting-stream-with-flink).

---

## License

ISC © 2026 Pham Ngoc Lam.
