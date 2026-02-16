# Production Monitoring Stack

This is a **complete, production-grade monitoring solution** for the Informix gRPC Proxy.

## Architecture Overview

```
┌─────────────────────────────────────────────────────────────────┐
│                     MONITORING STACK                              │
├─────────────────────────────────────────────────────────────────┤
│                                                                   │
│  ┌──────────────┐    ┌──────────────┐    ┌──────────────┐      │
│  │   Grafana    │◄───│  Prometheus  │◄───│  Informix    │      │
│  │ Dashboards   │    │   Metrics    │    │   Proxy      │      │
│  │              │    │              │    │              │      │
│  │  Port: 3000  │    │  Port: 9091  │    │  Port: 9090  │      │
│  └──────────────┘    └──────┬───────┘    └──────────────┘      │
│                              │                                   │
│                              │                                   │
│  ┌──────────────┐    ┌──────▼───────┐    ┌──────────────┐      │
│  │ Alertmanager │◄───│              │───►│ Node Exporter│      │
│  │  Routing     │    │  Scrapes:    │    │ System Stats │      │
│  │              │    │              │    │              │      │
│  │  Port: 9093  │    │              │    │  Port: 9100  │      │
│  └──────────────┘    └──────┬───────┘    └──────────────┘      │
│                              │                                   │
│                              │                                   │
│  ┌──────────────┐    ┌──────▼───────┐    ┌──────────────┐      │
│  │    Loki      │◄───│  Promtail    │    │   cAdvisor   │      │
│  │ Log Storage  │    │ Log Shipper  │    │ Container    │      │
│  │              │    │              │    │  Metrics     │      │
│  │  Port: 3100  │    │              │    │  Port: 8080  │      │
│  └──────────────┘    └──────────────┘    └──────────────┘      │
│                                                                   │
└─────────────────────────────────────────────────────────────────┘
```

## Components

### 1. **Prometheus** (Metrics Collection)
- **Port**: 9091
- **Purpose**: Scrapes and stores time-series metrics
- **Config**: `/monitoring/prometheus.yml`
- **Scrapes**:
    - Informix Proxy (every 15s)
    - Node Exporter (system metrics)
    - cAdvisor (container metrics)
    - Self-monitoring
- **Retention**: 30 days

### 2. **Grafana** (Visualization)
- **Port**: 3000
- **Credentials**: admin/admin (change in production!)
- **Purpose**: Visual dashboards for metrics
- **Pre-configured**:
    - Prometheus datasource
    - Loki datasource (logs)
    - Informix Proxy dashboard

### 3. **Alertmanager** (Alert Routing)
- **Port**: 9093
- **Purpose**: Routes alerts to correct teams
- **Receivers**:
    - Slack (team notifications)
    - PagerDuty (critical alerts)
    - Email (team-specific)
- **Features**:
    - Alert grouping
    - Inhibition rules (prevent alert storms)
    - Silencing

### 4. **Node Exporter** (System Metrics)
- **Port**: 9100
- **Purpose**: Export system-level metrics
- **Metrics**:
    - CPU usage
    - Memory usage
    - Disk I/O
    - Network traffic

### 5. **cAdvisor** (Container Metrics)
- **Port**: 8080
- **Purpose**: Container resource usage
- **Metrics**:
    - Container CPU
    - Container memory
    - Container network
    - Container filesystem

### 6. **Loki** (Log Aggregation)
- **Port**: 3100
- **Purpose**: Centralized log storage
- **Features**:
    - Log indexing
    - Full-text search
    - Integration with Grafana

### 7. **Promtail** (Log Shipping)
- **Purpose**: Ships logs to Loki
- **Sources**:
    - Docker container logs
    - System logs

## Quick Start

### 1. Start the Entire Stack

```bash
# Start everything (Informix DB + Proxy + Monitoring)
docker-compose -f docker-compose.prod.yml up -d

# Check all services are running
docker-compose -f docker-compose.prod.yml ps
```

Expected output:
```
NAME                    STATUS              PORTS
informix-test-db        Up (healthy)        9088, 9089, 27017-27018, 27883
informix-grpc-proxy     Up (healthy)        50051, 9090
informix-prometheus     Up                  9091
informix-grafana        Up                  3000
informix-alertmanager   Up                  9093
informix-node-exporter  Up                  9100
informix-cadvisor       Up                  8080
informix-loki           Up                  3100
informix-promtail       Up
```

### 2. Access the Dashboards

**Grafana** (Main UI):
```
http://localhost:3000
Login: admin / admin
```

**Prometheus** (Metrics UI):
```
http://localhost:9091
```

**Alertmanager** (Alerts UI):
```
http://localhost:9093
```

### 3. View Metrics in Grafana

1. Open http://localhost:3000
2. Login with admin/admin
3. Navigate to **Dashboards** → **Informix** → **Informix gRPC Proxy - Overview**

You'll see:
- **Connection Pool Utilization** gauge
- **Query Rate** graph
- **Proxy Status** indicator
- **Connection Pool State** bar chart
- **Query Latency Percentiles** (p50, p95, p99)
- **Error Rate** graph
- **JVM Heap Memory** usage
- **JVM Threads** count

## Metrics Explained

### Connection Pool Metrics

```
hikaricp_connections_active
  → Currently active connections

hikaricp_connections_idle
  → Idle connections waiting to be used

hikaricp_connections_max
  → Maximum pool size (configured limit)

hikaricp_connections_min
  → Minimum pool size (always maintained)

hikaricp_connections_pending
  → Requests waiting for a connection

hikaricp_connections_timeout_total
  → Total number of connection timeouts
```

**Why it matters**: Pool exhaustion = blocked requests = slow application

### Query Performance Metrics

```
informix_query_duration_seconds
  → Histogram of query execution times

informix_queries_total
  → Counter of all executed queries

informix_errors_total
  → Counter of query errors
```

**Query Latency Percentiles**:
- **p50** (median): Half of queries complete faster
- **p95**: 95% of queries complete faster (target: <1s)
- **p99**: 99% of queries complete faster (watch for outliers)

### JVM Metrics

```
jvm_memory_used_bytes{area="heap"}
  → Current heap usage

jvm_memory_max_bytes{area="heap"}
  → Maximum heap size (-Xmx setting)

jvm_threads_current
  → Active threads (watch for leaks)

jvm_gc_collection_seconds_sum
  → Time spent in garbage collection
```

**Red flags**:
- Heap usage >85% = increase -Xmx
- GC time >10% = memory pressure
- Thread count increasing = thread leak

## Alerts Reference

### Critical Alerts (PagerDuty)

| Alert | Threshold | Action |
|-------|-----------|--------|
| `ConnectionPoolExhausted` | ≥95% capacity | Increase pool size immediately |
| `InformixProxyDown` | Service unreachable | Check logs, restart service |
| `DatabaseConnectionFailing` | No new connections | Check Informix DB status |
| `CriticalErrorRate` | >20% queries failing | Investigate application/DB |

### Warning Alerts (Slack)

| Alert | Threshold | Action |
|-------|-----------|--------|
| `ConnectionPoolHighUtilization` | >80% capacity | Plan to increase pool size |
| `SlowQueriesDetected` | p95 latency >5s | Optimize queries, add indexes |
| `HighErrorRate` | >5% queries failing | Check application logs |
| `JVMHeapUsageHigh` | >85% heap used | Increase heap or investigate leak |

### Info Alerts (Email)

| Alert | Threshold | Action |
|-------|-----------|--------|
| `ConnectionPoolIdleTooHigh` | >60% idle | Consider reducing min idle |
| `NoActiveClients` | 0 clients for 30min | Verify service is needed |

## Customization

### 1. Configure Slack Notifications

Edit `monitoring/alertmanager.yml`:

```yaml
receivers:
  - name: 'team-notifications'
    slack_configs:
      - api_url: 'https://hooks.slack.com/services/YOUR/WEBHOOK/HERE'
        channel: '#your-channel'
```

Get webhook URL: https://api.slack.com/messaging/webhooks

### 2. Configure PagerDuty

Edit `monitoring/alertmanager.yml`:

```yaml
receivers:
  - name: 'pagerduty-critical'
    pagerduty_configs:
      - service_key: 'YOUR_PAGERDUTY_INTEGRATION_KEY'
```

Get key: PagerDuty → Services → Your Service → Integrations

### 3. Configure Email Alerts

Edit `monitoring/alertmanager.yml`:

```yaml
global:
  smtp_smarthost: 'smtp.gmail.com:587'
  smtp_from: 'alerts@yourdomain.com'
  smtp_auth_username: 'alerts@yourdomain.com'
  smtp_auth_password: 'your-app-password'
```

### 4. Add Custom Metrics

In your Java code:

```java
import io.prometheus.client.Counter;
import io.prometheus.client.Histogram;

// Define metrics
static final Counter customQueries = Counter.build()
    .name("informix_custom_queries_total")
    .help("Custom query counter")
    .register();

static final Histogram customDuration = Histogram.build()
    .name("informix_custom_duration_seconds")
    .help("Custom query duration")
    .register();

// Use in code
Histogram.Timer timer = customDuration.startTimer();
try {
    // Your code
    customQueries.inc();
} finally {
    timer.observeDuration();
}
```

### 5. Create Custom Dashboards

1. In Grafana, create new dashboard
2. Add panel
3. Query: `rate(informix_queries_total[5m])`
4. Save dashboard
5. Export JSON to `monitoring/grafana/dashboards/`

## Troubleshooting

### No Data in Grafana

**Check Prometheus is scraping:**
```bash
# Open Prometheus
http://localhost:9091

# Go to Status → Targets
# All targets should be "UP"
```

**Check proxy is exposing metrics:**
```bash
curl http://localhost:9090/metrics
# Should return Prometheus metrics
```

### Alerts Not Firing

**Test alert rule:**
```bash
# In Prometheus UI
http://localhost:9091/alerts

# Check alert state:
# - Inactive (green): Not firing
# - Pending (yellow): Condition met, waiting for 'for' duration
# - Firing (red): Alert active
```

**Test Alertmanager:**
```bash
# View alerts in Alertmanager
http://localhost:9093

# Should show active alerts
```

### High Memory Usage

**Prometheus**:
```yaml
# Reduce retention in prometheus.yml
command:
  - '--storage.tsdb.retention.time=7d'  # Reduced from 30d
```

**Grafana**:
```yaml
# Set memory limit in docker-compose
deploy:
  resources:
    limits:
      memory: 256M  # Reduced from 512M
```

## Production Best Practices

### 1. Secure Grafana

```yaml
environment:
  - GF_SECURITY_ADMIN_PASSWORD=STRONG_PASSWORD_HERE
  - GF_USERS_ALLOW_SIGN_UP=false
  - GF_AUTH_ANONYMOUS_ENABLED=false
```

### 2. Enable TLS

```yaml
# Add to Grafana config
environment:
  - GF_SERVER_PROTOCOL=https
  - GF_SERVER_CERT_FILE=/etc/grafana/ssl/cert.pem
  - GF_SERVER_CERT_KEY=/etc/grafana/ssl/key.pem
```

### 3. Backup Prometheus Data

```bash
# Backup Prometheus data volume
docker run --rm -v prometheus-data:/data -v $(pwd):/backup \
  ubuntu tar czf /backup/prometheus-backup.tar.gz /data
```

### 4. External Alert Storage

For long-term alert history, use Thanos or Cortex.

## Useful Queries

### Connection Pool Analysis

```promql
# Average pool utilization over 1 hour
avg_over_time(informix:hikaricp:pool_utilization_percent[1h])

# Connection timeout rate
rate(hikaricp_connections_timeout_total[5m])

# Connection acquisition time (p95)
histogram_quantile(0.95, 
  rate(hikaricp_connections_acquire_seconds_bucket[5m])
)
```

### Query Performance Analysis

```promql
# Queries per second
rate(informix_queries_total[5m])

# Slow query percentage (>1s)
sum(rate(informix_query_duration_seconds_bucket{le="1.0"}[5m]))
/
sum(rate(informix_query_duration_seconds_count[5m]))

# Error percentage
rate(informix_errors_total[5m]) 
/ 
rate(informix_queries_total[5m]) * 100
```

### JVM Analysis

```promql
# Heap utilization
jvm_memory_used_bytes{area="heap"} 
/ 
jvm_memory_max_bytes{area="heap"} * 100

# GC pressure (time spent in GC)
rate(jvm_gc_collection_seconds_sum[5m]) * 100

# Thread growth rate
deriv(jvm_threads_current[5m])
```

## Next Steps

1. **Customize alerts** for your thresholds
2. **Add dashboards** for specific use cases
3. **Configure notifications** (Slack, PagerDuty, email)
4. **Set up log aggregation** with Loki queries
5. **Create runbooks** linked in alert annotations
6. **Document baselines** for your normal load
7. **Test alerts** with load testing tools

## Support

- **Prometheus docs**: https://prometheus.io/docs/
- **Grafana docs**: https://grafana.com/docs/
- **Alertmanager docs**: https://prometheus.io/docs/alerting/latest/alertmanager/

---

**You now have enterprise-grade observability for your Informix gRPC Proxy!** 📊