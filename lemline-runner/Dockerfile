# syntax=docker/dockerfile:1.7
FROM eclipse-temurin:17-jdk-jammy AS build

WORKDIR /workspace

ARG GRADLE_MAX_HEAP=6g
ARG GRADLE_MAX_METASPACE=1g
ARG GRADLE_MAX_WORKERS=8

# Copy Gradle wrappers and root build config first for better layer caching.
COPY gradlew gradlew.bat settings.gradle.kts gradle.properties ./
COPY gradle ./gradle
COPY buildSrc ./buildSrc

# Copy only modules needed to build lemline-runner and its transitive dependencies.
COPY lemline-common ./lemline-common
COPY lemline-core ./lemline-core
COPY lemline-messages-proto ./lemline-messages-proto
COPY lemline-runner-config ./lemline-runner-config
COPY lemline-runner-common ./lemline-runner-common
COPY lemline-runner-cli ./lemline-runner-cli
COPY lemline-runner-analytics ./lemline-runner-analytics
COPY lemline-runner-definitions ./lemline-runner-definitions
COPY lemline-runner-failures ./lemline-runner-failures
COPY lemline-runner-forks ./lemline-runner-forks
COPY lemline-runner-gateway ./lemline-runner-gateway
COPY lemline-runner-listeners ./lemline-runner-listeners
COPY lemline-runner-messaging-pgmq ./lemline-runner-messaging-pgmq
COPY lemline-runner-parents ./lemline-runner-parents
COPY lemline-runner-retries ./lemline-runner-retries
COPY lemline-runner-schedules ./lemline-runner-schedules
COPY lemline-runner-waits ./lemline-runner-waits
COPY lemline-runner ./lemline-runner

RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew --no-daemon --console=plain \
    -Dorg.gradle.jvmargs="-Xmx${GRADLE_MAX_HEAP} -XX:MaxMetaspaceSize=${GRADLE_MAX_METASPACE} -Dfile.encoding=UTF-8" \
    -Dorg.gradle.workers.max="${GRADLE_MAX_WORKERS}" \
    :lemline-runner:quarkusBuild

FROM eclipse-temurin:17-jre-jammy

WORKDIR /app

RUN groupadd --system lemline && useradd --system --gid lemline --create-home lemline

COPY --from=build /workspace/lemline-runner/build/quarkus-app /app/quarkus-app

RUN chown -R lemline:lemline /app

USER lemline

ENTRYPOINT ["java", "-jar", "/app/quarkus-app/quarkus-run.jar"]
CMD ["listen"]
