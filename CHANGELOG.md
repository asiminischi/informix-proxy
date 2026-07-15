# Changelog

All notable changes to this project will be documented in this file.

Format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [1.1.0] - 2026-07-15

### Fixed
- `PoolManager.createPool` no longer leaks a `HikariDataSource` when the post-creation validation connection fails
- `ConnectionService.connect` no longer leaks an entire connection pool when the server-version lookup fails after pool creation - the pool is now removed if the client can never learn its id
- `TransactionService.beginTransaction` no longer leaks a `Connection` if `setAutoCommit`/`setTransactionIsolation` fails before the connection is registered
- `TransactionService.commit`/`rollback` now always return the connection to the pool, even if `commit()`/`rollback()` itself throws
- `PreparedStatementService.prepareStatement` no longer caches an unreachable, permanently-open statement+connection when building the response fails after preparing the statement
- `ConnectionService.disconnect` now discards any open transaction and closes any prepared statements still associated with the connection id before tearing down the pool, instead of leaving them as unreachable entries in `TransactionService` and `PreparedStatementCache` for the life of the JVM

### Changed
- The `informix-proxy` service no longer accepts or requires any Informix host/credential environment variables (`INFORMIX_HOST`, `INFORMIX_PORT`, `INFORMIX_USER`, `INFORMIX_PASSWORD`, etc.) in `docker-compose.prod.yml`, `docker-compose.dev.yml`, `.env.example`, or `PORTAINER_STACK.yml` - these were already unused dead configuration, since every client supplies its own credentials per-request via the `Connect` RPC. The proxy now holds no database credentials of its own.
- Fixed a naming mismatch where `docker-compose.prod.yml`, `docker-compose.dev.yml`, and `.env.example` set `POOL_SIZE`, `POOL_MAX_LIFETIME`, and `CONNECTION_TIMEOUT`, none of which matched the environment variable names `PoolConfig` actually reads (`POOL_MAX_SIZE`, `POOL_MAX_LIFETIME_MS`, `POOL_CONNECTION_TIMEOUT_MS`) - pool tuning via these files was silently ignored and always fell back to hardcoded defaults

## [1.0.0] - 2026-02-25

### Added
- gRPC proxy server (Java 11) with HikariCP connection pooling
- Protocol Buffer service definition with 14 RPC methods: Connect, Disconnect, Ping, ExecuteQuery, ExecuteUpdate, ExecuteBatch, PrepareStatement, ExecutePrepared, ClosePrepared, BeginTransaction, Commit, Rollback, GetMetadata
- Server-streaming query results with configurable fetch size
- Transaction support with configurable isolation levels (READ_UNCOMMITTED, READ_COMMITTED, REPEATABLE_READ, SERIALIZABLE)
- Prepared statement support with parameter binding
- Prometheus metrics: query counters, latency histograms, connection pool gauges, JVM metrics
- Grafana dashboards: Informix gRPC Proxy dashboard and Infrastructure dashboard (auto-provisioned)
- Alertmanager integration with template alert rules
- Loki + Promtail log aggregation
- Node.js client library (`clients/nodejs/informix-client.js`)
- Python client library (`clients/python/informix_client.py`)
- Docker Compose stack with Informix, proxy, and full monitoring suite
- Portainer-compatible stack definition (`PORTAINER_STACK.yml`)
- Externalized connection config via `.env` file support
- Database initialization sidecar (creates test database with sample data and stored procedures)
- Comprehensive documentation: architecture, deployment, development, clients, monitoring, migration

### Changed
- **This service replaces [`informixdbservice`](https://github.com/asiminischi/informixdbservice)**, which is now deprecated

### Removed
- Stray `health.proto` from project root (unused)
- Duplicate root-level `package.json` and `requirements.txt` (client-specific versions remain in `clients/`)
