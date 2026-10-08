# syntax=docker/dockerfile:1.7
#
# Granary: multi-stage build.
#   1. ui      - builds the Vue single-page application with Node (no Node download inside Maven)
#   2. build   - compiles and packages the Spring Boot server with Maven
#   3. runtime - a small JRE image running as a non-root user with /data as the state directory
#
# All base images can be swapped at build time, e.g. for a local mirror when Docker Hub is unreachable:
#   docker build --build-arg JRE_IMAGE=mirror.example.com/eclipse-temurin:25-jre-alpine .

ARG NODE_IMAGE=node:22-alpine
ARG MAVEN_IMAGE=maven:3.9-eclipse-temurin-25
ARG JRE_IMAGE=eclipse-temurin:25-jre-alpine

# ---------------------------------------------------------------- 1. web UI
FROM ${NODE_IMAGE} AS ui
WORKDIR /ui
COPY granary-ui/package.json granary-ui/package-lock.json ./
RUN --mount=type=cache,target=/root/.npm npm ci --no-audit --no-fund
COPY granary-ui/ ./
RUN npm run build

# ---------------------------------------------------------------- 2. server
FROM ${MAVEN_IMAGE} AS build
WORKDIR /src
COPY pom.xml ./
COPY granary-core/pom.xml granary-core/
COPY granary-format-maven/pom.xml granary-format-maven/
COPY granary-format-npm/pom.xml granary-format-npm/
COPY granary-format-nuget/pom.xml granary-format-nuget/
COPY granary-ui/pom.xml granary-ui/
COPY granary-server/pom.xml granary-server/
# Resolve dependencies first so source changes do not invalidate the download layer.
RUN --mount=type=cache,target=/root/.m2 mvn -B -q -Dui.skip=true dependency:go-offline || true
COPY . .
COPY --from=ui /ui/dist granary-ui/dist
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -q -Dui.skip=true -DskipTests package \
    && cp granary-server/target/granary.jar /granary.jar

# ---------------------------------------------------------------- 3. runtime
FROM ${JRE_IMAGE} AS runtime
LABEL org.opencontainers.image.title="Granary" \
      org.opencontainers.image.description="Offline-first artifact repository manager for Maven, npm and NuGet" \
      org.opencontainers.image.source="https://github.com/AhadPorkar/granary" \
      org.opencontainers.image.licenses="BUSL-1.1"

RUN addgroup -S granary && adduser -S -G granary -h /opt/granary granary \
    && mkdir -p /data && chown granary:granary /data \
    && apk add --no-cache curl

WORKDIR /opt/granary
COPY --from=build --chown=granary:granary /granary.jar granary.jar

USER granary
ENV GRANARY_DATA_DIR=/data \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+UseZGC -Djava.security.egd=file:/dev/./urandom"
VOLUME ["/data"]
EXPOSE 8081

HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
    CMD curl -fsS http://localhost:8081/actuator/health/readiness || exit 1

ENTRYPOINT ["java", "-jar", "/opt/granary/granary.jar"]
