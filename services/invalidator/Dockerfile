# syntax=docker/dockerfile:1.7
FROM maven:3.9.8-eclipse-temurin-21 AS build
WORKDIR /workspace

COPY pom.xml ./
COPY services/common/pom.xml      services/common/pom.xml
COPY services/writer/pom.xml      services/writer/pom.xml
COPY services/reader/pom.xml      services/reader/pom.xml
COPY services/invalidator/pom.xml services/invalidator/pom.xml
COPY tests/stress/pom.xml         tests/stress/pom.xml

RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -ntp -pl services/invalidator -am dependency:go-offline -DskipTests

COPY services/common/src      services/common/src
COPY services/invalidator/src services/invalidator/src
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -ntp -pl services/invalidator -am package -DskipTests

FROM gcr.io/distroless/java21-debian12:debug-nonroot
COPY --from=build /workspace/services/invalidator/target/cache-invalidator-exec.jar /app.jar
USER nonroot:nonroot
EXPOSE 8090
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-XX:+ExitOnOutOfMemoryError", "-jar", "/app.jar"]
