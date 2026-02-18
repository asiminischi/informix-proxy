FROM maven:3.9-eclipse-temurin-11 AS builder

WORKDIR /app

# Copy pom.xml and download dependencies (cached layer)
COPY pom.xml .
RUN mvn dependency:go-offline

# Copy proto files and generate code
COPY src/main/proto/ /app/proto/
RUN mvn protobuf:compile protobuf:compile-custom

# Copy source code
COPY src/ /app/src/

# Build the application
RUN mvn package -DskipTests

# Runtime stage
FROM eclipse-temurin:11-jre-jammy

WORKDIR /app

# Install curl for health checks
RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/*

# Copy the fat JAR from builder
COPY --from=builder /app/target/informix-grpc-proxy-1.0.0.jar /app/proxy.jar

# Expose gRPC and metrics ports
EXPOSE 50051 9090

# Health check using metrics endpoint
HEALTHCHECK --interval=30s --timeout=5s --start-period=30s --retries=3 \
  CMD curl -sf http://localhost:9090/metrics > /dev/null || exit 1

# Run the proxy
ENTRYPOINT ["java", "-jar", "/app/proxy.jar"]

# Default environment variables
ENV GRPC_PORT=50051
ENV JAVA_OPTS="-Xmx512m -Xms256m"