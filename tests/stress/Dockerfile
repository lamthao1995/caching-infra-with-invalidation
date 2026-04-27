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
    mvn -B -ntp -pl tests/stress -am dependency:go-offline -DskipTests

COPY services/common/src services/common/src
COPY tests/stress/src    tests/stress/src
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -ntp -pl tests/stress -am package -DskipTests

FROM eclipse-temurin:21-jre
COPY --from=build /workspace/tests/stress/target/cache-stress.jar /app.jar
ENTRYPOINT ["java", "-jar", "/app.jar"]
