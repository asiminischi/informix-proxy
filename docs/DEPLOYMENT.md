# Deployment

## Development (Docker Compose)

Start everything:

```
docker compose up -d
```

Wait about 60 seconds for Informix to initialize and the db-init sidecar to create the test database. Check status:

```
docker ps --format "table {{.Names}}\t{{.Status}}"
```

All services should show "Up" or "healthy". The `db-init` container will show "Exited (0)" after it finishes -- that is expected.

Stop everything:

```
docker compose down
```

Stop and delete all data (volumes):

```
docker compose down -v
```

## Rebuilding the proxy

After changing Java source or pom.xml:

```
docker compose build informix-proxy
docker compose up -d informix-proxy
```

## Services and ports

| Service | Port | Description |
|---------|------|-------------|
| informix-db | 9088 | Informix SQLI protocol |
| informix-db | 9089 | Informix DRDA protocol |
| informix-db | 27018 | REST Wire Listener |
| informix-proxy | 50051 | gRPC API |
| informix-proxy | 9090 | Prometheus metrics |
| prometheus | 9091 | Prometheus UI (mapped from 9090 inside) |
| grafana | 3000 | Grafana dashboards (admin/admin) |
| alertmanager | 9093 | Alertmanager UI |
| node-exporter | 9100 | Host metrics |
| cadvisor | 8080 | Container metrics |
| loki | 3100 | Log aggregation API |

## Environment variables

The proxy reads these from docker-compose.yml:

| Variable | Default | Description |
|----------|---------|-------------|
| GRPC_PORT | 50051 | gRPC server listen port |
| METRICS_PORT | 9090 | Prometheus metrics HTTP port |
| JAVA_OPTS | -Xmx512m -Xms256m | JVM arguments |
| DEFAULT_INFORMIX_HOST | informix-db | Default Informix host |
| DEFAULT_INFORMIX_PORT | 9088 | Default Informix port |
| DEFAULT_INFORMIX_DB | testdb | Default database name |
| DEFAULT_INFORMIX_USER | informix | Default username |
| DEFAULT_INFORMIX_PASS | in4mix | Default password |

## Container resources

Configured in docker-compose.yml under `deploy.resources.limits`:

| Service | CPU | Memory |
|---------|-----|--------|
| informix-db | 2 | 2G |
| informix-proxy | 4 | 4G |
| prometheus | 1 | 1G |
| grafana | 1 | 512M |
| loki | 1 | 512M |

Informix also requires `shm_size: 1g` for shared memory.

## Volumes

| Volume | Mounted in | Purpose |
|--------|-----------|---------|
| informix-data | informix-db | Database files |
| prometheus-data | prometheus | Metrics history (30 day retention) |
| grafana-data | grafana | Dashboard state and settings |
| alertmanager-data | alertmanager | Alert state |
| loki-data | loki | Log storage |

## Production considerations

Informix is running in privileged mode because the `ibmcom/informix-developer-database` image requires it for shared memory. In production, consider:

- Running Informix on a dedicated host or VM instead of Docker
- Using an external Informix instance and pointing the proxy at it
- Setting proper resource limits
- Configuring TLS for the gRPC endpoint
- Setting strong Informix and Grafana passwords
- Enabling alert receivers in alertmanager.yml (email, Slack, webhooks)
- Backing up the informix-data volume

## Portainer

The stack can be deployed through Portainer by pasting the contents of `docker-compose.yml` into a new stack. Set the stack name and configure any environment variable overrides in the Portainer UI.

## Windows notes

On Windows with Docker Desktop:

- cAdvisor may not collect all container metrics since it relies on Linux cgroup paths. The container will run but some panels in the Infrastructure dashboard may show empty.
- node-exporter similarly exposes limited metrics when running inside Docker Desktop's Linux VM.
- Both still show as "UP" in Prometheus targets. Use the proxy dashboard for the most useful data.
