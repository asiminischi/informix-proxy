# Contributing

## Branch strategy

In practice this repo uses a simpler flow than a full `develop`/`feature/*` GitFlow: work happens directly on a `release/x.y.z` branch until it's ready to ship.

| Branch | Purpose |
|--------|---------|
| `master` | Last released, stable code. Protected - merges via PR only. Updated when a `release/*` branch ships. |
| `release/x.y.z` | Active line of work for the next version. Branch from `master`, commit directly (or via short-lived PRs), merge to `master` and tag when ready. |
| `bugfix/*` | Bug fixes against a specific released version. Branch from `master` or the relevant `release/*`. |

## Workflow

1. Create or continue a release branch from `master`:
   ```
   git checkout master
   git pull
   git checkout -b release/x.y.z
   ```

2. Make changes, commit with clear messages:
   ```
   git commit -m "feat: add query timeout support"
   ```

3. Push the branch:
   ```
   git push -u origin release/x.y.z
   ```

4. When the release is ready, merge `release/x.y.z` into `master` and tag it (`vX.Y.Z`), matching the entry added to `CHANGELOG.md`.

## Commit messages

Follow [Conventional Commits](https://www.conventionalcommits.org/):

```
feat:     new feature
fix:      bug fix
docs:     documentation only
refactor: code change that neither fixes a bug nor adds a feature
test:     adding or updating tests
chore:    build, CI, or tooling changes
```

Examples:
```
feat: add gRPC TLS support
fix: JDBC URL separator for Informix SQLI connections
docs: add migration guide from informixdbservice
chore: remove stray health.proto from root
```

## Code style

- **Java**: Standard Java conventions. Single class for now (`InformixProxyServer.java`). Add metrics instrumentation for any new RPC method.
- **Proto**: Follow the [Google Protobuf style guide](https://protobuf.dev/programming-guides/style/).
- **Node.js / Python clients**: Keep them thin wrappers around the generated gRPC stubs.

## Testing

Before submitting a PR:

```bash
# Build the proxy
mvn clean package

# Start the stack
docker compose up -d

# Wait for Informix init (~60s), then run client tests
cd clients/nodejs && npm install && npm test
```

## Documentation

If your change affects usage, update the relevant doc in `docs/`. Key files:

| File | Covers |
|------|--------|
| `README.md` | Overview, quick start |
| `docs/ARCHITECTURE.md` | Design, protocol, metrics |
| `docs/CLIENTS.md` | Client library usage |
| `docs/DEPLOYMENT.md` | Docker, ports, env vars |
| `docs/DEVELOPMENT.md` | Building, IDE setup, adding RPCs |
| `docs/MONITORING.md` | Prometheus, Grafana, alerting |
| `docs/MIGRATION.md` | Migrating from informixdbservice |
