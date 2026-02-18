# Monitoring

## Components

- **Prometheus** (port 9091) -- scrapes metrics from the proxy, node-exporter, cadvisor, and alertmanager every 15 seconds
- **Grafana** (port 3030) -- dashboards, provisioned automatically from JSON files
- **Alertmanager** (port 9093) -- receives alerts from Prometheus, currently configured for log-only (no external notifications)
- **Loki** (port 3100) -- log aggregation
- **Promtail** -- ships Docker container logs to Loki

## Accessing the dashboards

Open http://localhost:3030 and log in with admin/admin.

Two dashboards are provisioned automatically:

### Informix gRPC Proxy dashboard

Found under the "Informix" folder. Shows:

- **Service Overview row**: proxy up/down status, active connections, query rate, error rate, heap memory used, uptime
- **Connection Pool row**: active/idle/total JDBC connections over time, pending threads
- **Query Performance row**: query rate by type (query, update, batch, prepared), latency percentiles (p50, p95, p99)
- **gRPC Methods row**: request rate by method, average latency by method
- **Transactions row**: begin/commit/rollback rates, error rate
- **JVM row**: heap memory with max line, thread count, GC time rate

### Infrastructure dashboard

Shows scrape target health (up/down) for all monitored services, Prometheus internals (time series count, scrape durations, ingestion rate, storage size).

## Prometheus targets

Check http://localhost:9091/targets to see scrape status. All targets should show "UP":

| Job | Target | What it scrapes |
|-----|--------|----------------|
| informix-proxy | informix-proxy:9090 | Proxy application + JVM + pool metrics |
| prometheus | localhost:9090 | Prometheus self-metrics |
| node-exporter | node-exporter:9100 | Host OS metrics |
| cadvisor | cadvisor:8080 | Docker container metrics |
| alertmanager | alertmanager:9093 | Alertmanager metrics |

The proxy job has a metric_relabel_configs rule that keeps only metrics matching `hikaricp_.*|grpc_.*|jvm_.*|informix_.*|process_.*` to avoid ingesting unneeded metrics.

## Available metrics from the proxy

These are exposed at `informix-proxy:9090/metrics`:

### Application metrics

```
informix_connections_active          -- gauge: active connection pools
informix_queries_total{type="..."}   -- counter: query/update/batch/prepared
informix_query_errors_total          -- counter: total errors
informix_transactions_total{type="..."} -- counter: begin/commit/rollback
grpc_server_handled_total{method="...",status="..."} -- counter: requests by method
grpc_server_handling_seconds_bucket{method="...",le="..."} -- histogram: latency
```

### Connection pool metrics

```
informix_pool_active_connections     -- gauge: active JDBC connections
informix_pool_idle_connections       -- gauge: idle connections
informix_pool_total_connections      -- gauge: total connections
informix_pool_pending_threads        -- gauge: threads waiting for a connection
```

### JVM metrics (from prometheus simpleclient_hotspot)

```
jvm_memory_bytes_used{area="heap|nonheap"}
jvm_memory_bytes_max{area="heap|nonheap"}
jvm_threads_current
jvm_gc_collection_seconds_sum
jvm_gc_collection_seconds_count
process_cpu_seconds_total
process_start_time_seconds
process_resident_memory_bytes
```

## Useful PromQL queries

Query rate (requests per second):
```
sum(rate(informix_queries_total[5m]))
```

95th percentile latency:
```
histogram_quantile(0.95, sum(rate(grpc_server_handling_seconds_bucket[5m])) by (le))
```

Error percentage:
```
rate(informix_query_errors_total[5m]) / sum(rate(informix_queries_total[5m])) * 100
```

Pool utilization:
```
informix_pool_active_connections / informix_pool_total_connections
```

JVM heap usage percentage:
```
jvm_memory_bytes_used{area="heap"} / jvm_memory_bytes_max{area="heap"} * 100
```

## Alerting

Alert rules are defined in `monitoring/alerts.yml`. They are currently disabled (the file contains `groups: []`). Commented-out rules in that file cover:

- Connection pool exhaustion (>80%, >95%)
- Slow queries (p95 > 5s, p99 > 30s)
- High error rate (>5%, >20%)
- JVM heap pressure (>85%)
- Service downtime
- Long-running transactions

To enable alerts:

1. Uncomment the rule groups in `monitoring/alerts.yml`
2. Configure a receiver in `monitoring/alertmanager.yml` (email, Slack, webhook)
3. Restart both containers: `docker compose restart prometheus alertmanager`

The alertmanager.yml file has commented-out examples for Slack and email receivers.

## Log aggregation

Loki collects logs from all Docker containers via Promtail. In Grafana, switch the datasource to "Loki" and use the Explore view to search logs.

Filter by container:
```
{container="informix-proxy"}
```

Filter errors:
```
{container="informix-proxy"} |= "error"
```

## Configuration files

| File | Purpose |
|------|---------|
| prometheus.yml (root) | Prometheus scrape config, mounted into container |
| monitoring/alerts.yml | Alert rules (currently empty/disabled) |
| monitoring/alertmanager.yml | Alert routing and receivers |
| monitoring/grafana/dashboards/*.json | Dashboard definitions |
| monitoring/grafana/provisioning/datasources/datasources.yml | Prometheus and Loki datasource config |
| monitoring/grafana/provisioning/dashboards/dashboards.yml | Dashboard auto-provisioning |
| monitoring/loki-config.yaml | Loki storage and retention |
| monitoring/promtail-config.yaml | Promtail scrape targets |
