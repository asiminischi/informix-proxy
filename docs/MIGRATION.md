# Migrating from informixdbservice

This guide covers switching from the legacy [`informixdbservice`](https://github.com/asiminischi/informixdbservice) to `informix-proxy`.

## Why migrate

| | informixdbservice (old) | informix-proxy (new) |
|---|---|---|
| Protocol | REST / HTTP | gRPC (protobuf) |
| Connection pooling | None (one connection per request) | HikariCP pools per session |
| Streaming | No | Server-streaming for large result sets |
| Transactions | Limited | Full support with isolation levels |
| Prepared statements | No | Yes |
| Monitoring | None | Prometheus metrics, Grafana dashboards, alerting |
| Clients | HTTP calls | Typed gRPC clients (Node.js, Python, any language) |

## Steps

### 1. Deploy the proxy

Add the `informix-proxy` service to your Docker Compose stack or deploy the Portainer stack (see [DEPLOYMENT.md](DEPLOYMENT.md)).

The proxy listens on:
- **Port 50051** — gRPC API (replaces the old REST endpoint)
- **Port 9090** — Prometheus metrics

### 2. Install the client library

**Node.js:**
```bash
# Copy or reference clients/nodejs/informix-client.js in your project
npm install @grpc/grpc-js @grpc/proto-loader
```

**Python:**
```bash
pip install grpcio grpcio-tools protobuf
# Generate stubs — see docs/CLIENTS.md
```

### 3. Update your application code

Replace HTTP calls to `informixdbservice` with gRPC calls through `InformixClient`.

**Before (informixdbservice — REST):**
```javascript
const response = await fetch('http://informixdbservice:3000/query', {
    method: 'POST',
    body: JSON.stringify({ sql: 'SELECT * FROM customer' })
});
const data = await response.json();
```

**After (informix-proxy — gRPC):**
```javascript
const InformixClient = require('./informix-client');

const client = new InformixClient('informix-proxy', 50051);
await client.connect({
    host: 'informix-db',
    port: 9088,
    database: 'testdb',
    username: 'informix',
    password: 'in4mix'
});

const result = await client.query('SELECT * FROM customer');
console.log(result.rows);

await client.disconnect();
```

### 4. Update Docker networking

The new proxy resolves the Informix host internally. Your application containers only need network access to the **proxy** (port 50051), not to Informix directly.

```yaml
# In your application's docker-compose.yml
services:
  your-app:
    environment:
      INFORMIX_PROXY_HOST: informix-proxy
      INFORMIX_PROXY_PORT: 50051
    networks:
      - informix
```

### 5. Remove the old service

Once all consumers have migrated:

1. Remove `informixdbservice` from your Docker Compose / Portainer stack
2. Remove any direct JDBC or REST references to the old service
3. Update firewall rules — close the old service port, ensure 50051 is reachable

### 6. Verify

```bash
cd clients/nodejs
npm install
node test-client.js   # validates connectivity end-to-end
```

Check Grafana at `http://localhost:3000` for query metrics confirming traffic flows through the new proxy.

## Key differences to be aware of

- **Connection lifecycle**: The old service was stateless (one request = one connection). The new proxy uses persistent connection pools. Call `connect()` once at startup and `disconnect()` on shutdown.
- **Error handling**: Errors come back as gRPC status codes, not HTTP status codes. The client libraries throw standard errors with messages.
- **Parameterized queries**: Use `?` placeholders and pass parameters as an array. The proxy uses JDBC prepared statements — no more string concatenation.
- **Streaming**: For large result sets, use `queryStream()` to process rows incrementally instead of loading everything into memory.

## Timeline

- **Now**: `informix-proxy` is the supported service for all new development.
- **Deprecation**: `informixdbservice` will receive no further updates.
- **Removal**: The old repository will be archived after all projects have migrated.
