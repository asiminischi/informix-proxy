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

## Staging deployment (current state and planned decoupling)

**Current state (as of 2026-07-29):** the staging `informix-proxy` container is not deployed from this repo. It runs as a co-located service inside `presa-management`'s own staging stack (`postarodiy/presa-management`, `docker/staging/docker-compose.yml`), pulling `ghcr.io/postarodiy/informix-proxy:${INFORMIX_PROXY_TAG}`. Updating it means manually bumping that tag and restarting the stack from the presa-management side - nothing in this repo triggers it.

This is despite `publish.yml` already having a complete `deploy` job (build -> test -> Trivy scan -> push -> cosign sign -> deploy) that:
- targets `runs-on: [self-hosted, staging]`, the same runner presa-management's own CD uses
- verifies the cosign signature before deploying (refuses to run an image that wasn't built by this exact workflow)
- deploys via `cd /opt/informix-proxy && docker compose pull && up -d`

That job has never actually run end to end: `/opt/informix-proxy` does not exist on the staging box, and the job's trigger (`push` to `release/**`, or manual `workflow_dispatch` with `deploy: true`) has apparently never fired. It was built ahead of when it was needed.

**The staging runner** is the same physical box presa-management deploys to. It was renamed from `staging` to `stage@devops` on 2026-07-29 to make room for a future multi-machine naming convention (`<env>@<hostname>`, e.g. a later `prod@<hostname>`). Labels are unchanged (`self-hosted`, `staging`, `Linux`, `X64`), so this repo's `deploy` job's `runs-on: [self-hosted, staging]` still resolves to it without any workflow change.

**Why decoupling is lower-risk than it looks:** presa-app talks to informix-proxy over gRPC using two plain env vars, `INFORMIX_GRPC_HOST` / `INFORMIX_GRPC_PORT` (see presa-management's `.env.example`). Docker Compose currently overrides `INFORMIX_GRPC_HOST` to the Docker-internal service name `informix-proxy`, relying on both containers sharing the `staging_internal` bridge network - but that's a convenience, not a hard requirement. This repo's own `docker-compose.prod.yml` already publishes the gRPC port to the host (`${GRPC_PORT:-50051}:50051`), so once informix-proxy runs independently, presa-app just needs `INFORMIX_GRPC_HOST` pointed at the host address instead of the Docker network. No shared Docker network between the two stacks is required.

**Groundwork still needed before cutting over** (deliberately not done yet - staging currently works, this is prep for a later, deliberate migration, not an in-place change):
1. Fix the stale default image owner in this file's `informix-proxy.image` fallback (`ghcr.io/asiminischi/informix-proxy` -> `ghcr.io/postarodiy/informix-proxy` - same org-transfer staleness that hit presa-management's compose files).
2. Confirm the `deploy` job's trigger branch (`release/**`) actually matches how this repo intends to cut staging releases - `master` might be more appropriate if there's no release-branch workflow in practice.
3. Create `/opt/informix-proxy` on the staging box (compose file + `.env`) so the existing `deploy` job has somewhere to land.
4. Switch presa-management's staging `INFORMIX_GRPC_HOST` from the Docker service name to the host address, then remove the co-located `informix-proxy` service from presa-management's `docker/staging/docker-compose.yml`.
5. Cut over, verify presa-app can still reach the proxy, decommission the old co-located container.

## Portainer

The stack can be deployed through Portainer by pasting the contents of `docker-compose.yml` into a new stack. Set the stack name and configure any environment variable overrides in the Portainer UI.

## Windows notes

On Windows with Docker Desktop:

- cAdvisor may not collect all container metrics since it relies on Linux cgroup paths. The container will run but some panels in the Infrastructure dashboard may show empty.
- node-exporter similarly exposes limited metrics when running inside Docker Desktop's Linux VM.
- Both still show as "UP" in Prometheus targets. Use the proxy dashboard for the most useful data.
