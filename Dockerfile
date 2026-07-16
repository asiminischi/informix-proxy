# =============================================================================
# informix-proxy — Multi-stage Dockerfile
# Stage 1: Build the fat JAR with Maven
# Stage 2: Minimal JRE runtime with a curl-based health check
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

# curl is required at runtime for the HEALTHCHECK below, and is what every
# docker-compose file in this repo already uses for its own healthcheck
# against the metrics endpoint. "apt-get upgrade" pulls in any OS package
# security patches published after the base image was built (e.g. libssl3)
# instead of shipping whatever was baked into eclipse-temurin:11-jre-jammy.
RUN apt-get update \
  && apt-get upgrade -y \
  && apt-get install -y --no-install-recommends curl ca-certificates \
  && apt-get clean \
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
    METRICS_PORT=9090 \
    JAVA_OPTS="-Xmx512m -Xms256m -XX:+UseG1GC -XX:MaxGCPauseMillis=200 -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=/tmp"

# Health check against the Prometheus metrics endpoint - the same check
# every docker-compose file in this repo already uses. This used to shell
# out to grpcurl instead, which dragged in ~28 CRITICAL/HIGH CVEs from its
# embedded Go toolchain and dependencies (upstream hasn't cut a release
# since March 2025) to run a check that compose always overrode anyway.
HEALTHCHECK --interval=30s --timeout=10s --start-period=30s --retries=5 \
  CMD curl -sf http://localhost:${METRICS_PORT}/metrics || exit 1

# Shell form so $JAVA_OPTS is expanded at runtime
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/proxy.jar"]
