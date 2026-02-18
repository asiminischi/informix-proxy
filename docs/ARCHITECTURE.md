# Architecture

## Overview

```
  Node.js / Python / Go / any gRPC client
              |
              | gRPC (port 50051)
              v
    +-------------------+
    | Informix gRPC     |
    | Proxy (Java 11)   |
    |                   |
    | - gRPC server     |
    | - HikariCP pools  |
    | - Prometheus HTTP  |
    +-------------------+
         |          |
         | JDBC     | HTTP (port 9090)
         | (9088)   |
         v          v
    Informix DB   Prometheus -> Grafana
```

The proxy is a single Java class (`InformixProxyServer.java`) that extends the generated gRPC service stub. It uses Netty for gRPC transport and HikariCP for JDBC connection pooling. Prometheus metrics are served on a separate HTTP port using the simpleclient library.

## Protocol

The gRPC service is defined in `src/main/proto/informix.proto`. Clients send a `Connect` request with host, port, database, username, and password. The proxy creates a HikariCP connection pool for that session and returns a connection ID. All subsequent calls (queries, updates, transactions) reference that connection ID.

Query results are streamed. The proxy reads rows in batches (configurable fetch size), serializes them into protobuf messages, and sends them as a server-streaming response. This avoids loading entire result sets into memory.

## Connection pooling

Each `Connect` call creates a separate HikariCP pool. Default settings:

| Setting | Value |
|---------|-------|
| Max pool size | 10 (client-configurable) |
| Min idle | 2 |
| Connection timeout | 30 seconds |
| Idle timeout | 10 minutes |
| Max lifetime | 30 minutes |
| Test query | `SELECT 1 FROM systables WHERE tabid = 1` |

Pools are destroyed when the client calls `Disconnect`.

## Transaction handling

When a client calls `BeginTransaction`, the proxy pulls a connection from the pool, disables auto-commit, and holds onto it. All queries and updates on that connection ID use the held connection until `Commit` or `Rollback` is called.

Supported isolation levels: READ_UNCOMMITTED, READ_COMMITTED, REPEATABLE_READ, SERIALIZABLE.

## Metrics

The proxy exposes Prometheus metrics on the port specified by the `METRICS_PORT` environment variable (default 9090). Metrics include:

**Application metrics** (from proxy code):
- `informix_connections_active` -- gauge, number of active connection pools
- `informix_queries_total{type}` -- counter, queries by type (query, update, batch, prepared)
- `informix_query_errors_total` -- counter, total failed queries
- `informix_transactions_total{type}` -- counter, transactions by type (begin, commit, rollback)
- `grpc_server_handled_total{method,status}` -- counter, gRPC calls by method and outcome
- `grpc_server_handling_seconds{method}` -- histogram, gRPC call latency

**Pool metrics** (from HikariCP, collected at scrape time):
- `informix_pool_active_connections` -- active JDBC connections
- `informix_pool_idle_connections` -- idle JDBC connections
- `informix_pool_total_connections` -- total connections in all pools
- `informix_pool_pending_threads` -- threads waiting for a connection

**JVM metrics** (from Prometheus hotspot collector):
- `jvm_memory_bytes_used{area}` -- heap and non-heap memory
- `jvm_threads_current` -- live threads
- `jvm_gc_collection_seconds_sum` -- time spent in GC
- `process_cpu_seconds_total` -- CPU usage
- `process_start_time_seconds` -- process uptime

## Database initialization

The `db-init` service is a sidecar container that runs after Informix starts. It uses the same Informix image (so it has `dbaccess` available) and connects to the Informix server over the Docker network. The init script (`scripts/init-db.sh`) creates the test database, tables, sample data, stored procedures, and grants permissions.

The init is idempotent. If the `testdb` database already exists, the script skips creation. To reset, use `docker compose down -v` to remove the data volume.

Informix does not support multi-row INSERT syntax (`VALUES (...), (...)`). Each INSERT is a separate statement.

## Authentication

Informix uses OS-level trust authentication for SQLI connections (port 9088). The db-init sidecar needs to be trusted by the Informix server. This is handled by mounting a `.rhosts` file into the Informix container that trusts the `informix` user from any host on the Docker network.

JDBC connections from the proxy use username/password authentication passed through the connection string.
