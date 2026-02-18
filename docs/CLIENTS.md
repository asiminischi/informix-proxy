# Client Libraries

## Node.js

### Install

```
cd clients/nodejs
npm install
```

Dependencies: `@grpc/grpc-js`, `@grpc/proto-loader`.

### Usage

```javascript
const InformixClient = require('./informix-client');

const client = new InformixClient('localhost', 50051);

// Connect
const conn = await client.connect({
    host: 'informix-db',
    port: 9088,
    database: 'testdb',
    username: 'informix',
    password: 'in4mix',
    poolSize: 10
});
console.log('Server:', conn.serverVersion);

// Query (returns all rows)
const result = await client.query(
    'SELECT * FROM customer WHERE customer_id < ?',
    [5]
);
console.log(result.rows);

// Stream (calls onRow for each row, useful for large result sets)
await client.queryStream(
    'SELECT * FROM customer',
    [],
    (row) => console.log(row.first_name, row.last_name),
    { fetchSize: 50 }
);

// Insert/Update/Delete
const affected = await client.execute(
    'INSERT INTO customer (first_name, last_name, email) VALUES (?, ?, ?)',
    ['Test', 'User', 'test@example.com']
);

// Transaction
await client.beginTransaction();
try {
    await client.execute('UPDATE products SET stock_quantity = stock_quantity - 1 WHERE product_id = ?', [1]);
    await client.commit();
} catch (e) {
    await client.rollback();
}

// Metadata
const tables = await client.getMetadata();        // list all tables
const cols = await client.getMetadata('customer'); // describe one table

// Disconnect
await client.disconnect();
```

### Running the example

```
cd clients/nodejs
node informix-client.js
```

This runs the built-in example function that connects, queries customers, streams results, runs a transaction, and disconnects.

### Connection config

When calling `client.connect()`, the `host` should be the Informix server hostname as seen from inside the Docker network. If the client runs on the host machine and the proxy runs in Docker, use `localhost` for the proxy and `informix-db` for the Informix host (the proxy resolves `informix-db` via Docker DNS).

When running the client from outside Docker:

```javascript
const client = new InformixClient('localhost', 50051); // proxy address
await client.connect({
    host: 'informix-db',  // resolved by the proxy, not your machine
    port: 9088,
    database: 'testdb',
    username: 'informix',
    password: 'in4mix'
});
```

### Parameter types

The client automatically converts JavaScript types to protobuf parameters:

| JS type | Protobuf type |
|---------|---------------|
| string | string_value |
| integer (Number.isInteger) | int_value |
| float | double_value |
| boolean | bool_value |
| Buffer | bytes_value |
| null/undefined | is_null |

## Python

### Install

```
cd clients/python
pip install -r requirements.txt
```

You also need to generate the Python protobuf stubs:

```
python -m grpc_tools.protoc \
    -I../../src/main/proto \
    --python_out=. \
    --grpc_python_out=. \
    ../../src/main/proto/informix.proto
```

This generates `informix_pb2.py` and `informix_pb2_grpc.py` in the current directory.

### Usage

```python
from informix_client import InformixClient

client = InformixClient('localhost', 50051)
client.connect(
    host='informix-db',
    port=9088,
    database='testdb',
    username='informix',
    password='in4mix'
)

rows = client.query('SELECT * FROM customer')
for row in rows:
    print(row)

client.disconnect()
```

The Python client is less developed than the Node.js one. See `clients/python/informix_client.py` for the full API.
