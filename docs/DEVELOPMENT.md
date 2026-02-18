# Development

## Prerequisites

- Java 11 (JDK)
- Maven 3.6+
- Docker and Docker Compose
- Node.js 14+ (for the Node.js client)

## Building from source

```
mvn clean package
```

This runs the protobuf compiler to generate Java classes from `src/main/proto/informix.proto`, compiles the proxy server, and produces a fat JAR at `target/informix-grpc-proxy-1.0.0.jar`.

If you only want to generate the protobuf classes without a full build:

```
mvn protobuf:compile protobuf:compile-custom
```

The generated Java files land in `target/generated-sources/protobuf/`.

## IDE setup

The generated protobuf classes live in `target/generated-sources/`. Your IDE needs to know about them.

**IntelliJ IDEA**: right-click `target/generated-sources/protobuf/java` and `target/generated-sources/protobuf/grpc-java`, then Mark Directory as > Generated Sources Root. Or run `mvn compile` and IntelliJ usually picks them up automatically.

**VS Code with Java extensions**: run `mvn compile`, then Ctrl+Shift+P > "Java: Clean Java Language Server Workspace" and reload.

**Eclipse**: run `mvn eclipse:eclipse`, then refresh the project.

After `mvn clean`, the generated sources are deleted. Run `mvn compile` again to regenerate them.

## Project layout

```
src/
  main/
    java/com/informix/grpc/
      InformixProxyServer.java      -- the entire proxy server
    proto/
      informix.proto                -- gRPC service definition
    resources/
  test/
    groovy/                         -- test directory (empty)
    resources/

target/
  generated-sources/protobuf/
    java/                           -- generated message classes
    grpc-java/                      -- generated service stubs
  informix-grpc-proxy-1.0.0.jar    -- fat JAR after build
```

## Running locally (without Docker)

You need a reachable Informix instance. Set the environment:

```
set GRPC_PORT=50051
set METRICS_PORT=9090
java -jar target/informix-grpc-proxy-1.0.0.jar
```

Or run directly from Maven:

```
mvn compile exec:java -Dexec.mainClass=com.informix.grpc.InformixProxyServer
```

## Running the Node.js client tests

```
cd clients/nodejs
npm install
npm test
```

This runs `node informix-client.js` which exercises connect, query, stream, transaction, and disconnect operations against the running proxy.

## Docker build

The Dockerfile uses a multi-stage build:

1. Builder stage: Maven + JDK 11, runs `mvn package`
2. Runtime stage: JRE 11 only, copies the fat JAR

```
docker compose build informix-proxy
```

## Adding a new RPC method

1. Define the request/response messages and RPC in `src/main/proto/informix.proto`
2. Run `mvn compile` to regenerate stubs
3. Override the new method in `InformixProxyServer.java`
4. Add metrics instrumentation (counter + histogram timer)
5. Rebuild the Docker image

## Adding a new metric

The proxy uses the Prometheus simpleclient library (0.16.0). To add a metric:

```java
private static final Counter myCounter = Counter.build()
        .name("informix_my_metric_total")
        .help("Description")
        .labelNames("label1")
        .register();
```

Then increment it in the relevant method:

```java
myCounter.labels("value1").inc();
```

The metric will automatically appear at `/metrics` and be scraped by Prometheus. Use the `informix_` prefix so it passes the metric_relabel filter in prometheus.yml.

## Dependencies

Key dependencies (see pom.xml for versions):

| Library | Purpose |
|---------|---------|
| grpc-netty-shaded | gRPC transport |
| grpc-protobuf | Protobuf serialization for gRPC |
| grpc-stub | Generated stub base classes |
| HikariCP | JDBC connection pooling |
| com.ibm.informix:jdbc | Informix JDBC driver |
| simpleclient + simpleclient_hotspot + simpleclient_httpserver | Prometheus metrics |
| slf4j-simple | Logging |
