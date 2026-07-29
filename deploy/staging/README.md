# Staging deployment (`/opt/informix-proxy`)

This is the standalone deployment target for `publish.yml`'s `deploy` job.
It's separate from presa-management's own staging stack (`/opt/staging`) -
see `docs/DEPLOYMENT.md`'s "Staging deployment" section for why, and for the
groundwork already done vs. still pending.

## One-time bootstrap (do this before the first CI deploy)

```bash
# On the staging box:
mkdir -p /opt/informix-proxy
cd /opt/informix-proxy

# Copy these two files from a checkout of this repo:
#   deploy/staging/docker-compose.yml -> /opt/informix-proxy/docker-compose.yml
#   deploy/staging/.env.example       -> /opt/informix-proxy/.env

docker compose up -d
docker compose ps        # should show informix-proxy healthy
```

This also creates the `informix_proxy_net` Docker network. **Do this before**
switching presa-management's `docker/staging/docker-compose.yml` to join that
network as `external: true` - otherwise presa-app's stack will fail to start
with a "network not found" error.

## After bootstrap

Once `/opt/informix-proxy` exists and is healthy, every push to `release/**`
(or a manual `workflow_dispatch` with `deploy: true`) builds, scans, signs,
and deploys automatically via the `deploy` job in
`.github/workflows/publish.yml` - no manual steps needed after that.

## Verifying

```bash
docker compose exec informix-proxy curl -sf http://localhost:9090/metrics
```

From presa-management's `/opt/staging` stack (once it's joined
`informix_proxy_net`):

```bash
docker compose exec presa-app curl -sf http://informix-proxy:9090/metrics
```
