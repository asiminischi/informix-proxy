# =============================================================================
# informix-proxy — Multi-stage Dockerfile
# Stage 1: Build the fat JAR with Maven
# Stage 2: Minimal JRE runtime with grpcurl for health checks
# =============================================================================

# ── Stage 1: Build ────────────────────────────────────────────────────────────
FROM docker.io/library/maven:3.9-eclipse-temurin-11 AS builder

WORKDIR /app

# 1. Resolve dependencies first — this layer is cached until pom.xml changes
COPY pom.xml .
RUN mvn dependency:go-offline -B

# 2. Generate protobuf sources — cached until proto files change
COPY src/main/proto/ src/main/proto/
RUN mvn protobuf:compile protobuf:compile-custom -B

# 3. Build the fat JAR — only reruns when source changes
COPY src/ src/
RUN mvn package -DskipTests -B \
  && echo "✓ Built: $(ls -lh target/informix-grpc-proxy-*.jar)"

# ── Stage 2: Runtime ──────────────────────────────────────────────────────────
FROM eclipse-temurin:11-jre-jammy

# grpcurl version to install — pin for reproducibility
ARG GRPCURL_VERSION=1.9.1
ARG TARGETOS=linux
ARG TARGETARCH=amd64

# Install grpcurl (used for health checks and manual debugging)
# and clean up apt in the same layer to keep image size down
RUN apt-get update \
  && apt-get install -y --no-install-recommends curl ca-certificates \
  && DEB_ARCH=$(dpkg --print-architecture) \
  && case "${DEB_ARCH}" in \
       amd64)   GRPC_ARCH="x86_64"  ;; \
       arm64)   GRPC_ARCH="arm64"   ;; \
       armhf)   GRPC_ARCH="armv7"   ;; \
       *)       echo "Unsupported arch: ${DEB_ARCH}" && exit 1 ;; \
     esac \
  && curl -fsSL \
       "https://github.com/fullstorydev/grpcurl/releases/download/v${GRPCURL_VERSION}/grpcurl_${GRPCURL_VERSION}_${TARGETOS}_${GRPC_ARCH}.tar.gz" \
     | tar -xz --no-same-owner -C /usr/local/bin grpcurl \
  && chmod +x /usr/local/bin/grpcurl \
  && apt-get purge -y curl \
  && apt-get autoremove -y \
  && rm -rf /var/lib/apt/lists/*

WORKDIR /app

# Non-root user — never run a service as root
RUN groupadd --system proxygroup && useradd --system --gid proxygroup --no-create-home proxygroup
USER proxygroup

# Copy the fat JAR from the build stage
COPY --from=builder --chown=proxygroup:proxygroup /app/target/informix-grpc-proxy-1.1.0.jar proxy.jar

# gRPC service port
EXPOSE 50051
# Prometheus metrics port
EXPOSE 9090

# JAVA_OPTS is honoured via the shell-form entrypoint below.
# Override at runtime: docker run -e JAVA_OPTS="-Xmx1g" ...
ENV GRPC_PORT=50051 \
    JAVA_OPTS="-Xmx512m -Xms256m -XX:+UseG1GC -XX:MaxGCPauseMillis=200 -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=/tmp"

# Health check via gRPC health protocol — same check docker-compose uses
HEALTHCHECK --interval=30s --timeout=10s --start-period=30s --retries=5 \
  CMD grpcurl -plaintext localhost:${GRPC_PORT} grpc.health.v1.Health/Check || exit 1

# Shell form so $JAVA_OPTS is expanded at runtime
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/proxy.jar"]
