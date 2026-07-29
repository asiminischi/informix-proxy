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
| POOL_MAX_SIZE | 20 | Max JDBC connections per client pool |
| POOL_MIN_IDLE | 5 | Min idle JDBC connections per client pool |
| POOL_CONNECTION_TIMEOUT_MS | 30000 | Time to wait for a pooled connection |
| POOL_IDLE_TIMEOUT_MS | 600000 | Time an idle connection may sit before eviction |
| POOL_MAX_LIFETIME_MS | 1800000 | Max lifetime of a pooled connection |

The proxy holds no Informix host or credentials of its own - each client
supplies host/port/database/username/password in the `Connect` RPC, and the
proxy opens a connection pool for that client on demand. This means there is
nothing database-credential-shaped to configure or rotate at the proxy level.

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

## Staging deployment

`informix-proxy` deploys to its own directory on the staging box,
`/opt/informix-proxy`, independently of `presa-management`. See
`deploy/staging/` in this repo for the exact compose file, `.env` template,
and bootstrap steps.

`publish.yml`'s `deploy` job (build -> test -> Trivy scan -> push -> cosign
sign -> deploy) handles the rest automatically:
- targets `runs-on: [self-hosted, staging]`, the same runner presa-management's own CD uses (the runner's *name* changed to `stage@devops` on 2026-07-29 for a future multi-machine convention, but its labels - `self-hosted`, `staging`, `Linux`, `X64` - didn't, so this still resolves without a workflow change)
- verifies the cosign signature before deploying (refuses to run an image that wasn't built by this exact workflow)
- triggers on push to `release/**` (matches this repo's actual branch flow - see `CONTRIBUTING.md`) or manual `workflow_dispatch` with `deploy: true`
- deploys via `cd /opt/informix-proxy && docker compose pull && up -d`

**How presa-app reaches it:** both stacks run on the same physical staging
box. Rather than publishing the gRPC port to the host (which would drop
presa-management's current "zero external exposure" posture for a port with
no auth layer of its own - see `docs/ROADMAP.md`), the two compose projects
share a Docker bridge network, `informix_proxy_net`. `/opt/informix-proxy`
creates it; presa-management's `docker/staging/docker-compose.yml` joins it
as `external: true` and keeps reaching the proxy by container name
(`INFORMIX_GRPC_HOST=informix-proxy`), unchanged from today. This means
`/opt/informix-proxy` must be bootstrapped (and the network created) *before*
presa-management's stack is redeployed with its co-located `informix-proxy`
service removed - see `deploy/staging/README.md`.

**Status as of 2026-07-29:**
- Done: image owner path, CI hardening, this repo's `deploy/staging/` template, presa-management's side of the wiring (on a feature branch, not yet merged/deployed).
- Still pending (needs hands-on access to the staging box, not done from this session): bootstrap `/opt/informix-proxy` for the first time, then merge and deploy presa-management's decoupling branch, then verify and remove the old co-located container.

## Portainer

The stack can be deployed through Portainer by pasting the contents of `docker-compose.yml` into a new stack. Set the stack name and configure any environment variable overrides in the Portainer UI.

## Windows notes

On Windows with Docker Desktop:

- cAdvisor may not collect all container metrics since it relies on Linux cgroup paths. The container will run but some panels in the Infrastructure dashboard may show empty.
- node-exporter similarly exposes limited metrics when running inside Docker Desktop's Linux VM.
- Both still show as "UP" in Prometheus targets. Use the proxy dashboard for the most useful data.
