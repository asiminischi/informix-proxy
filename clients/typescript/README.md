# @postarodiy/informix-client

Typed Node.js/TypeScript client for informix-proxy's gRPC service. This is
the maintained client going forward - see `../nodejs` for the original
untyped JS client (kept for existing consumers, not actively extended).

## Install

```
npm install @postarodiy/informix-client
```

Requires a `.npmrc` pointing `@postarodiy` at GitHub Packages:

```
@postarodiy:registry=https://npm.pkg.github.com
//npm.pkg.github.com/:_authToken=${GITHUB_PACKAGES_TOKEN}
```

## Usage

```ts
import { InformixClient } from '@postarodiy/informix-client';

const client = new InformixClient('localhost', 50051);

const conn = await client.connect({
  host: 'informix-db',
  port: 9088,
  database: 'testdb',
  username: 'informix',
  password: 'in4mix',
  poolSize: 10,
});
console.log('Server:', conn.serverVersion);

const result = await client.query(
  'SELECT * FROM customer WHERE customer_id < ?',
  [5],
);
console.log(result.rows);

await client.queryStream(
  'SELECT * FROM customer',
  [],
  (row) => console.log(row.first_name, row.last_name),
  { fetchSize: 50 },
);

const affected = await client.execute(
  'INSERT INTO customer (first_name, last_name, email) VALUES (?, ?, ?)',
  ['Test', 'User', 'test@example.com'],
);

await client.beginTransaction();
try {
  await client.execute('UPDATE products SET stock_quantity = stock_quantity - 1 WHERE product_id = ?', [1]);
  await client.commit();
} catch (e) {
  await client.rollback();
}

const tables = await client.getMetadata();
const cols = await client.getMetadata('customer');

await client.disconnect();
```

## Connection self-healing

Every method that takes a `connection_id` resets the client's local state
(and best-effort releases the server-side pool via `Disconnect`) when the
proxy returns `UNAVAILABLE` or `INTERNAL` - both mean the connection itself
is dead, not just the one call. After that, `isConnected` is `false` and the
caller should `connect()` again before retrying.

`resetConnection()` and `disconnect()` always null the local connection id
*before* the release RPC settles (or even if it errors) - the id is
considered dead the moment either is called, regardless of whether the
proxy round-trip that releases its pool succeeds.

## Logging

Pass a `logger` in the constructor options to observe connect/disconnect/
reset events:

```ts
const client = new InformixClient('localhost', 50051, {
  logger: {
    info: (msg, meta) => myLogger.info(msg, meta),
    warn: (msg, meta) => myLogger.warn(msg, meta),
    error: (msg, meta) => myLogger.error(msg, meta),
  },
});
```

Defaults to a no-op logger.
