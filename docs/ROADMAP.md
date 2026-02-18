# Roadmap

What this project still needs to be production-ready, roughly ordered by importance.

## Security

**TLS for gRPC** -- The proxy currently accepts plaintext gRPC connections. Add TLS termination with server certificates. Eventually support mTLS so only trusted clients can connect.

**Authentication and authorization** -- There is no auth layer. Any client that can reach port 50051 has full database access. Options: gRPC interceptor that checks a bearer token or API key, or integrate with an identity provider for JWT validation.

**Informix credential management** -- The proxy builds JDBC URLs with hardcoded or env-var credentials. Move to a secrets manager (Vault, Docker secrets, AWS Secrets Manager) instead of passing passwords through environment variables.

**Query allow-listing** -- The proxy executes arbitrary SQL from clients. Consider a query validation layer or at minimum block DDL statements (DROP, ALTER, CREATE) unless explicitly allowed.

## Reliability

**Circuit breaker** -- If Informix goes down, the proxy keeps accepting connections and failing. Add a circuit breaker (Resilience4j or similar) that trips after N failures and returns a clear error instead of timing out.

**Rate limiting** -- No protection against a single client overwhelming the proxy. Add per-client rate limiting on the gRPC interceptor layer.

**Health check depth** -- The current health check only verifies the metrics HTTP endpoint responds. Add a deep health check that validates an actual Informix connection from the pool.

**Graceful shutdown** -- The shutdown hook closes the gRPC server, but in-flight requests may be dropped. Add a drain period that stops accepting new requests while finishing active ones.

**Connection leak detection** -- HikariCP has leak detection built in (leakDetectionThreshold). Enable it and expose a metric for detected leaks.

## Observability

**Structured logging** -- Replace slf4j-simple with logback and output JSON-structured logs. This makes log aggregation in Loki much more useful (filter by session ID, method, error type).

**Distributed tracing** -- Add OpenTelemetry traces to gRPC calls so you can follow a request from client through proxy to Informix and back. Integrate with Jaeger or Tempo.

**Query logging and audit** -- Log every SQL statement executed (with parameters redacted) for audit purposes. Expose as a toggle so it can be turned off in high-throughput scenarios.

**Dashboard alerting rules** -- The alertmanager config has placeholder rules. Define real alerts: proxy down for 2 minutes, error rate above 5% for 5 minutes, connection pool exhausted, heap usage above 80%.

## Performance

**Prepared statement caching** -- The proxy creates and closes prepared statements per request. Cache frequently-used statements to reduce Informix parse overhead.

**Connection pool tuning** -- The current pool settings (max 10 connections, 30s timeout) are defaults. Profile under load and tune to match actual Informix capacity and client concurrency.

**Load testing** -- No load tests exist. Use ghz (gRPC benchmarking tool) or a custom client to establish baseline throughput, latency percentiles, and breaking points.

**Response compression** -- Enable gRPC compression (gzip) for large result sets to reduce bandwidth between proxy and clients.

## Operations

**CI/CD pipeline** -- No build pipeline exists. Set up GitHub Actions or similar: lint, compile, test, build Docker image, push to registry, deploy.

**Container registry** -- The Docker image is only built locally. Push to a private registry (GitHub Container Registry, Docker Hub private, AWS ECR) for proper versioning and deployment.

**Kubernetes deployment** -- Docker Compose works for single-node. For multi-node production, create Helm charts or Kubernetes manifests with proper resource limits, readiness/liveness probes, and horizontal pod autoscaling.

**Backup strategy** -- The Informix data lives in a Docker volume. Define and automate a backup strategy (ontape, ON-Bar, or volume snapshots).

**Configuration management** -- Most config is in environment variables scattered across docker-compose files. Consolidate into a single config file or use a config service.

## Client libraries

**Go client** -- Generate Go stubs from the proto file and provide a reference client.

**C# / .NET client** -- Generate C# stubs. Useful for teams with .NET backends.

**Client SDK packaging** -- Publish the Node.js and Python clients as proper packages (npm, PyPI) with versioning, so consumers do not need to copy files around.

## Database

**Schema migrations** -- The init-db.sh script creates tables on first run. Add a migration framework (Flyway, Liquibase, or a simple versioned SQL script runner) for schema changes after initial deployment.

**Multi-database support** -- The proxy currently connects to a single Informix database. Support connecting to multiple databases or Informix instances through configuration.

**Read replicas** -- For read-heavy workloads, support routing SELECT queries to HDR or RSS secondaries while writes go to the primary.

## Nice to have

**Admin API** -- A REST or gRPC endpoint for runtime admin: view active sessions, kill a session, flush the connection pool, toggle query logging.

**Web UI** -- A lightweight web interface for ad-hoc queries and session inspection, built on top of the gRPC API.

**Proto documentation** -- Generate HTML documentation from the proto file comments using protoc-gen-doc.
