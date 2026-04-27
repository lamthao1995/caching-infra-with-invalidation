.PHONY: help build test cover \
        docker docker-writer docker-reader docker-invalidator docker-stress \
        k8s-kind k8s-images k8s-load k8s-up k8s-status k8s-down k8s-nuke \
        k8s-stress fmt tidy

# ─── Variables ───────────────────────────────────────────────────────────
KIND_CLUSTER ?= caching-infra
IMAGE_TAG    ?= 0.1.0
WRITER_IMG   := cache-writer:$(IMAGE_TAG)
READER_IMG   := cache-reader:$(IMAGE_TAG)
INVAL_IMG    := cache-invalidator:$(IMAGE_TAG)
STRESS_IMG   := cache-stress:$(IMAGE_TAG)

ENV_FILE     ?= .env
K8S_DIR      := deployments/k8s
NS           := caching-infra
COMPOSE      := docker compose --env-file $(ENV_FILE) -f deployments/docker-compose.yml

help:
	@echo "Build / test:"
	@echo "  make build              - mvn package (skip tests)"
	@echo "  make test               - mvn verify (unit + integration + jacoco)"
	@echo "  make cover              - open jacoco reports in your browser"
	@echo ""
	@echo "Docker images:"
	@echo "  make docker             - build all 4 images (writer / reader / invalidator / stress)"
	@echo ""
	@echo "Local docker-compose stack (recommended for stress runs):"
	@echo "  make compose-up         - build + start the full stack (mysql / kafka / connect / redis / 3 services)"
	@echo "  make compose-down       - stop the stack (keeps volumes)"
	@echo "  make compose-down-v     - stop the stack and wipe volumes (fresh DB on next up)"
	@echo "  make compose-logs       - tail logs"
	@echo "  make compose-stress     - run the stress generator against the running stack"
	@echo ""
	@echo "Local Kubernetes (kind):"
	@echo "  make k8s-kind           - create the kind cluster from kind-cluster.yaml"
	@echo "  make k8s-images         - == make docker"
	@echo "  make k8s-load           - side-load the 4 images into the kind cluster"
	@echo "  make k8s-up             - kubectl apply -k $(K8S_DIR)"
	@echo "  make k8s-status         - kubectl get pods,svc,hpa -n $(NS)"
	@echo "  make k8s-down           - kubectl delete -k $(K8S_DIR) (keeps PVCs)"
	@echo "  make k8s-nuke           - delete the entire kind cluster"
	@echo "  make k8s-stress         - run cache-stress against the in-cluster ingress"

$(ENV_FILE):
	cp .env.example $(ENV_FILE)

# ─── Maven ───────────────────────────────────────────────────────────────
build:
	mvn -B -ntp -DskipTests package

test:
	mvn -B -ntp verify

cover:
	@find . -path '*/target/site/jacoco/index.html' | xargs -I{} echo "open {}"

fmt:
	@echo "(no formatter wired — add spotless if you want one)"

tidy:
	mvn -B -ntp -U dependency:purge-local-repository -DreResolve=true

# ─── Docker images ───────────────────────────────────────────────────────
# Each image is built from the *workspace root* (.) so the Dockerfile can copy the
# parent POM + the shared common module + its own service in one shot.
docker: docker-writer docker-reader docker-invalidator docker-stress

docker-writer:
	docker build -f services/writer/Dockerfile      -t $(WRITER_IMG) .

docker-reader:
	docker build -f services/reader/Dockerfile      -t $(READER_IMG) .

docker-invalidator:
	docker build -f services/invalidator/Dockerfile -t $(INVAL_IMG)  .

docker-stress:
	docker build -f tests/stress/Dockerfile         -t $(STRESS_IMG) .

# ─── Local docker-compose stack ──────────────────────────────────────────
.PHONY: compose-up compose-down compose-down-v compose-logs compose-stress compose-status

compose-up: $(ENV_FILE)
	$(COMPOSE) up -d --build
	@echo
	@echo "stack is starting; tail with 'make compose-logs'"
	@echo "writer  : http://localhost:$${WRITER_HOST_PORT:-8080}"
	@echo "reader  : http://localhost:$${READER_HOST_PORT:-8081}"
	@echo "connect : http://localhost:8083"

compose-status:
	$(COMPOSE) ps

compose-down:
	$(COMPOSE) down

compose-down-v:
	$(COMPOSE) down -v

compose-logs:
	$(COMPOSE) logs -f --tail=200

compose-stress: $(ENV_FILE)
	$(COMPOSE) --profile stress run --rm stress

# ─── Kubernetes (local kind) ─────────────────────────────────────────────
k8s-kind:
	kind create cluster --config $(K8S_DIR)/kind-cluster.yaml --name $(KIND_CLUSTER)

k8s-images: docker

k8s-load:
	kind load docker-image $(WRITER_IMG) --name $(KIND_CLUSTER)
	kind load docker-image $(READER_IMG) --name $(KIND_CLUSTER)
	kind load docker-image $(INVAL_IMG)  --name $(KIND_CLUSTER)
	kind load docker-image $(STRESS_IMG) --name $(KIND_CLUSTER)

k8s-up:
	kubectl apply -k $(K8S_DIR)
	@echo
	@echo "waiting for rollouts (timeout 5m each) ..."
	kubectl -n $(NS) rollout status statefulset/mysql         --timeout=5m
	kubectl -n $(NS) rollout status statefulset/kafka         --timeout=5m
	kubectl -n $(NS) rollout status statefulset/kafka-connect --timeout=5m
	kubectl -n $(NS) rollout status statefulset/redis         --timeout=5m
	@echo "waiting for register-debezium Job ..."
	kubectl -n $(NS) wait --for=condition=complete job/register-debezium --timeout=5m
	kubectl -n $(NS) rollout status deployment/writer         --timeout=5m
	kubectl -n $(NS) rollout status deployment/reader         --timeout=5m
	kubectl -n $(NS) rollout status deployment/invalidator    --timeout=5m
	$(MAKE) k8s-status

k8s-status:
	@echo "── pods ─────────────────────────────────────────────────"
	@kubectl -n $(NS) get pods -o wide
	@echo
	@echo "── services / hpas ──────────────────────────────────────"
	@kubectl -n $(NS) get svc,hpa
	@echo
	@echo "── ingress ──────────────────────────────────────────────"
	@kubectl -n $(NS) get ingress

k8s-down:
	kubectl delete -k $(K8S_DIR) || true

k8s-nuke:
	kind delete cluster --name $(KIND_CLUSTER)

# Run the stress generator as a one-shot Job *inside* the cluster, against the
# ClusterIP services (avoids ingress as a measurement variable).
k8s-stress:
	kubectl -n $(NS) run stress --rm -i --tty --restart=Never \
		--image=$(STRESS_IMG) --image-pull-policy=IfNotPresent -- \
		--write-target=http://writer:8080 \
		--read-target=http://reader:8081 \
		--rps=$${STRESS_RPS:-2000} \
		--duration=$${STRESS_DURATION:-60s} \
		--workers=$${STRESS_WORKERS:-32} \
		--keyspace=$${STRESS_KEYSPACE:-10000} \
		--read-ratio=$${STRESS_READ_RATIO:-0.8} \
		--update-ratio=$${STRESS_UPDATE_RATIO:-0.1}
