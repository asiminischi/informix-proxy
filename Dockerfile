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

# Copy Informix JDBC driver
# You need to place the jdbc-4.50.10.jar file in the lib/ directory
#COPY lib/ /app/lib/

# Build the application
RUN mvn package -DskipTests

# Runtime stage
FROM eclipse-temurin:11-jre-jammy

WORKDIR /app

# Copy the fat JAR from builder
COPY --from=builder /app/target/informix-grpc-proxy-1.0.0.jar /app/proxy.jar

# Expose gRPC port
EXPOSE 50051

# Health check
HEALTHCHECK --interval=30s --timeout=3s --start-period=10s --retries=3 \
  CMD grpc-health-probe -addr=:50051 || exit 1

# Run the proxy
ENTRYPOINT ["java", "-jar", "/app/proxy.jar"]

# Default environment variables
ENV GRPC_PORT=50051
ENV JAVA_OPTS="-Xmx512m -Xms256m"