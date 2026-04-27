# syntax=docker/dockerfile:1.7
# ─── stage 1: build the Spring Boot fat jar ────────────────────────────────
# Build context = workspace root:
#   docker build -f services/writer/Dockerfile -t cache-writer:0.1.0 .
FROM maven:3.9.8-eclipse-temurin-21 AS build
WORKDIR /workspace

# All module POMs are copied first so the parent's <modules> stanza resolves cleanly and
# Maven can build a stable dependency graph in a single cached layer. Only the module(s)
# we actually compile need src/ — the rest stay as POM-only stubs.
COPY pom.xml ./
COPY services/common/pom.xml      services/common/pom.xml
COPY services/writer/pom.xml      services/writer/pom.xml
COPY services/reader/pom.xml      services/reader/pom.xml
COPY services/invalidator/pom.xml services/invalidator/pom.xml
COPY tests/stress/pom.xml         tests/stress/pom.xml

# BuildKit cache mount → Maven downloads survive across image builds. Without this every
# `docker build` re-downloads ~200 MB of deps from Central; with it the second build of any
# service finishes in seconds.
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -ntp -pl services/writer -am dependency:go-offline -DskipTests

COPY services/common/src services/common/src
COPY services/writer/src services/writer/src
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -ntp -pl services/writer -am package -DskipTests

# ─── stage 2: distroless runtime ───────────────────────────────────────────
# Use the `debug-nonroot` variant — includes busybox (~10 MB), which provides /bin/sh +
# wget so the docker-compose healthcheck can probe /actuator/health/readiness without us
# pulling in a full distro. K8s probes use kubelet's built-in httpGet and don't need this,
# but the same image runs in both places.
FROM gcr.io/distroless/java21-debian12:debug-nonroot
# Use the *-exec.jar (Spring Boot fat jar). The plain target/cache-writer.jar is left
# behind on the build stage so Failsafe IT tests can resolve test classes via the normal
# project classpath; see services/writer/pom.xml for the classifier rationale.
COPY --from=build /workspace/services/writer/target/cache-writer-exec.jar /app.jar
USER nonroot:nonroot
EXPOSE 8080
# MaxRAMPercentage lets the JVM size the heap from the cgroup limit instead of
# defaulting to 25% of the host RAM (which is wrong on every Kubernetes node).
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-XX:+ExitOnOutOfMemoryError", "-jar", "/app.jar"]
