# Informix gRPC Proxy

A gRPC proxy server that sits between your applications and an IBM Informix database. It handles connection pooling, query streaming, transactions, and exposes Prometheus metrics. Clients connect over gRPC (port 50051) instead of using Informix JDBC drivers directly.

## Why this exists

Informix JDBC drivers are platform-specific, poorly maintained, and hard to distribute. This proxy runs the JDBC driver in one place (a Java process) and exposes a clean gRPC API. Any language with a gRPC client (Node.js, Python, Go, etc.) can talk to Informix without needing the JDBC driver installed locally.

The proxy also adds connection pooling (HikariCP), query result streaming, prepared statement caching, and Prometheus metrics that are otherwise impossible to get from Informix directly.

## Quick start

Requirements: Docker and Docker Compose.

```
docker compose up -d
```

This starts:

- **informix-db** -- IBM Informix database (port 9088)
- **db-init** -- one-shot container that creates a test database with sample data, then exits
- **informix-proxy** -- the gRPC proxy (port 50051 for gRPC, port 9090 for metrics)
- **prometheus** -- metrics collection (port 9091)
- **grafana** -- dashboards (port 3000, login: admin/admin)
- **alertmanager** -- alert routing (port 9093)
- **node-exporter**, **cadvisor** -- host and container metrics
- **loki**, **promtail** -- log aggregation

Wait about 60 seconds for Informix to initialize. The db-init container will create the `testdb` database automatically.

## Test it

```
cd clients/nodejs
npm install
node informix-client.js
```

This connects to Informix through the proxy, runs queries against the test database, executes a transaction, and disconnects.

See [docs/CLIENTS.md](docs/CLIENTS.md) for client usage details.

## What gets created in the test database

The db-init container creates `testdb` with these tables:

| Table | Rows | Description |
|-------|------|-------------|
| customer | 10 | Customer records with contact info |
| products | 15 | Product catalog with prices and stock |
| orders | 10 | Orders linked to customers |
| order_items | 17 | Line items linked to orders and products |
| inventory_movements | 11 | Stock changes (restock, sale, adjustment) |

Plus three stored procedures: `get_customer_orders`, `calculate_order_total`, `get_customer_ltv`.

## gRPC API

The proxy exposes these RPC methods (defined in `src/main/proto/informix.proto`):

| Method | Description |
|--------|-------------|
| Connect | Open a connection pool to an Informix database |
| Disconnect | Close a connection pool |
| Ping | Health check with latency measurement |
| ExecuteQuery | Run a SELECT, results streamed in batches |
| ExecuteUpdate | Run INSERT/UPDATE/DELETE |
| ExecuteBatch | Run multiple SQL statements in one call |
| PrepareStatement | Create a reusable prepared statement |
| ExecutePrepared | Run a prepared statement |
| ClosePrepared | Release a prepared statement |
| BeginTransaction | Start a transaction (supports isolation levels) |
| Commit | Commit the active transaction |
| Rollback | Roll back the active transaction |
| GetMetadata | List tables or describe a table's columns |

## Monitoring

Grafana is at http://localhost:3000 (admin/admin). Two dashboards are provisioned automatically:

- **Informix gRPC Proxy** -- query rates, latency percentiles, connection pool state, gRPC method breakdown, JVM heap/threads/GC
- **Infrastructure** -- scrape target status, Prometheus internals

Prometheus is at http://localhost:9091. All proxy metrics are scraped from `informix-proxy:9090/metrics`.

See [docs/MONITORING.md](docs/MONITORING.md) for details on available metrics and alerting.

## Project structure

```
src/main/java/       Java gRPC proxy server (single class)
src/main/proto/      Protocol Buffer service definition
clients/nodejs/      Node.js gRPC client library and test
clients/python/      Python gRPC client library
scripts/             Database init script, setup helpers
monitoring/          Prometheus, Grafana, Alertmanager, Loki configs
docs/                Documentation
```

## Documentation

- [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) -- how the proxy works, protocol, connection pooling
- [docs/DEPLOYMENT.md](docs/DEPLOYMENT.md) -- Docker setup, production config, Portainer
- [docs/MONITORING.md](docs/MONITORING.md) -- Prometheus metrics, Grafana dashboards, alerting
- [docs/CLIENTS.md](docs/CLIENTS.md) -- using the Node.js and Python clients
- [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md) -- building from source, running tests, IDE setup
- [docs/ROADMAP.md](docs/ROADMAP.md) -- what is implemented and what is not

## License

MIT
