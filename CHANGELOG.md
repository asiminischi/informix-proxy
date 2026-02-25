# Changelog

All notable changes to this project will be documented in this file.

Format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

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
