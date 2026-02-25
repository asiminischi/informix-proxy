# Contributing

## Branch strategy

| Branch | Purpose |
|--------|---------|
| `master` | Stable, release-ready code. Protected — merges via PR only. |
| `develop` | Integration branch for features. PRs merge here first. |
| `feature/*` | New features or improvements. Branch from `develop`. |
| `bugfix/*` | Bug fixes. Branch from `develop` (or `master` for hotfixes). |
| `release/*` | Release candidates. Branch from `develop`, merge to `master` + `develop`. |

## Workflow

1. Create a feature branch from `develop`:
   ```
   git checkout develop
   git pull
   git checkout -b feature/my-feature
   ```

2. Make changes, commit with clear messages:
   ```
   git commit -m "feat: add query timeout support"
   ```

3. Push and open a PR against `develop`:
   ```
   git push -u origin feature/my-feature
   ```

4. After review and approval, squash-merge into `develop`.

5. When `develop` is ready for release, create a `release/x.y.z` branch, finalize, then merge to `master` and tag.

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
