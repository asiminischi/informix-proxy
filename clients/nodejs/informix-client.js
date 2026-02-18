const grpc = require('@grpc/grpc-js');
const protoLoader = require('@grpc/proto-loader');
const path = require('path');

// Load proto definition
const PROTO_PATH = path.join(__dirname, '../../src/main/proto/informix.proto');
const packageDefinition = protoLoader.loadSync(PROTO_PATH, {
    keepCase: true,
    longs: String,
    enums: String,
    defaults: true,
    oneofs: true
});

const informixProto = grpc.loadPackageDefinition(packageDefinition).informix;

class InformixClient {
    constructor(proxyHost = 'localhost', proxyPort = 50051) {
        this.client = new informixProto.InformixService(
            `${proxyHost}:${proxyPort}`,
            grpc.credentials.createInsecure()
        );
        this.connectionId = null;
    }

    async connect(config) {
        return new Promise((resolve, reject) => {
            const request = {
                host: config.host,
                port: config.port,
                database: config.database,
                username: config.username,
                password: config.password,
                properties: config.properties || {},
                pool_size: config.poolSize || 10
            };

            this.client.Connect(request, (error, response) => {
                if (error) { reject(error); return; }
                if (!response.success) { reject(new Error(response.error)); return; }
                this.connectionId = response.connection_id;
                resolve({
                    connectionId: response.connection_id,
                    serverVersion: response.server_version
                });
            });
        });
    }

    async disconnect() {
        if (!this.connectionId) return;
        return new Promise((resolve, reject) => {
            this.client.Disconnect({ connection_id: this.connectionId }, (error) => {
                if (error) { reject(error); return; }
                this.connectionId = null;
                resolve();
            });
        });
    }

    async ping() {
        this._checkConnection();
        return new Promise((resolve, reject) => {
            this.client.Ping({ connection_id: this.connectionId }, (error, response) => {
                if (error) { reject(error); return; }
                resolve({ alive: response.alive, latencyMs: response.latency_ms });
            });
        });
    }

    async query(sql, params = [], options = {}) {
        this._checkConnection();
        return new Promise((resolve, reject) => {
            const request = {
                connection_id: this.connectionId,
                sql: sql,
                parameters: this._convertParameters(params),
                fetch_size: options.fetchSize || 100,
                max_rows: options.maxRows || 0
            };

            const rows = [];
            let columns = null;
            let totalRows = 0;
            const call = this.client.ExecuteQuery(request);

            call.on('data', (response) => {
                if (response.error) { reject(new Error(response.error)); return; }
                if (response.columns && response.columns.length > 0) columns = response.columns;
                if (response.rows) {
                    response.rows.forEach(row => {
                        const obj = {};
                        row.values.forEach((value, index) => {
                            const colName = columns[index].name;
                            obj[colName] = this._convertValue(value);
                        });
                        rows.push(obj);
                    });
                }
                totalRows = response.total_rows;
            });

            call.on('end', () => resolve({ rows, columns, rowCount: totalRows }));
            call.on('error', (error) => reject(error));
        });
    }

    async queryStream(sql, params = [], onRow, options = {}) {
        this._checkConnection();
        return new Promise((resolve, reject) => {
            const request = {
                connection_id: this.connectionId,
                sql: sql,
                parameters: this._convertParameters(params),
                fetch_size: options.fetchSize || 100,
                max_rows: options.maxRows || 0
            };

            let columns = null;
            let totalRows = 0;
            const call = this.client.ExecuteQuery(request);

            call.on('data', (response) => {
                if (response.error) { reject(new Error(response.error)); return; }
                if (response.columns && response.columns.length > 0) columns = response.columns;
                if (response.rows) {
                    response.rows.forEach(row => {
                        const obj = {};
                        row.values.forEach((value, index) => {
                            const colName = columns[index].name;
                            obj[colName] = this._convertValue(value);
                        });
                        onRow(obj);
                    });
                }
                totalRows = response.total_rows;
            });

            call.on('end', () => resolve({ columns, rowCount: totalRows }));
            call.on('error', (error) => reject(error));
        });
    }

    async execute(sql, params = []) {
        this._checkConnection();
        return new Promise((resolve, reject) => {
            const request = {
                connection_id: this.connectionId,
                sql: sql,
                parameters: this._convertParameters(params)
            };

            this.client.ExecuteUpdate(request, (error, response) => {
                if (error) { reject(error); return; }
                if (response.error) { reject(new Error(response.error)); return; }
                resolve(response.rows_affected);
            });
        });
    }

    async batch(sqlStatements) {
        this._checkConnection();
        return new Promise((resolve, reject) => {
            const request = {
                connection_id: this.connectionId,
                sql_statements: sqlStatements
            };

            this.client.ExecuteBatch(request, (error, response) => {
                if (error) { reject(error); return; }
                if (response.error) { reject(new Error(response.error)); return; }
                resolve(response.rows_affected);
            });
        });
    }

    async beginTransaction(isolationLevel = 'READ_COMMITTED') {
        this._checkConnection();
        return new Promise((resolve, reject) => {
            const request = { connection_id: this.connectionId, isolation_level: isolationLevel };
            this.client.BeginTransaction(request, (error, response) => {
                if (error) { reject(error); return; }
                if (!response.success) { reject(new Error(response.error)); return; }
                resolve();
            });
        });
    }

    async commit() {
        this._checkConnection();
        return new Promise((resolve, reject) => {
            this.client.Commit({ connection_id: this.connectionId }, (error, response) => {
                if (error) { reject(error); return; }
                if (!response.success) { reject(new Error(response.error)); return; }
                resolve();
            });
        });
    }

    async rollback() {
        this._checkConnection();
        return new Promise((resolve, reject) => {
            this.client.Rollback({ connection_id: this.connectionId }, (error, response) => {
                if (error) { reject(error); return; }
                if (!response.success) { reject(new Error(response.error)); return; }
                resolve();
            });
        });
    }

    async getMetadata(tableName = '') {
        this._checkConnection();
        return new Promise((resolve, reject) => {
            const request = { connection_id: this.connectionId, table_name: tableName };
            this.client.GetMetadata(request, (error, response) => {
                if (error) { reject(error); return; }
                if (response.error) { reject(new Error(response.error)); return; }
                resolve(response.tables);
            });
        });
    }

    _checkConnection() {
        if (!this.connectionId) throw new Error('Not connected to database. Call connect() first.');
    }

    _convertParameters(params) {
        return params.map(param => {
            if (param === null || param === undefined) return { is_null: true };
            if (typeof param === 'string') return { string_value: param };
            if (typeof param === 'number') return Number.isInteger(param) ? { int_value: param } : { double_value: param };
            if (typeof param === 'boolean') return { bool_value: param };
            if (Buffer.isBuffer(param)) return { bytes_value: param };
            return { string_value: String(param) };
        });
    }

    _convertValue(value) {
        if (value.is_null) return null;
        if (value.string_data !== undefined && value.string_data !== '') return value.string_data;
        if (value.int_data !== undefined) return value.int_data;
        if (value.long_data !== undefined) return parseInt(value.long_data);
        if (value.double_data !== undefined) return value.double_data;
        if (value.bool_data !== undefined) return value.bool_data;
        if (value.bytes_data !== undefined) return Buffer.from(value.bytes_data);
        return null;
    }
}

// Example usage updated for stores_demo schema
async function example() {
    const client = new InformixClient('localhost', 50051); // gRPC proxy host:port

    try {
        // Connect to the Informix database through the proxy
        const connInfo = await client.connect({
            host: 'informix-db',
            port: 9088,
            database: 'testdb',
            username: 'informix',
            password: 'in4mix',
            poolSize: 10
        });
        console.log('Connected:', connInfo.serverVersion);

        // Simple query using correct column names
        const result = await client.query('SELECT * FROM customer WHERE customer_id < ?', [105]);
        console.log('Customers (sample):', result.rows);

        // Streaming large results with correct field usage
        console.log('Streaming all customers:');
        await client.queryStream(
            'SELECT * FROM customer',
            [],
            (row) => {
                console.log(' -', row.first_name, row.last_name);
            },
            { fetchSize: 50 }
        );

        // Transaction example (columns updated to match schema)
        await client.beginTransaction();
        try {
            await client.execute(
                'INSERT INTO customer (first_name, last_name, email) VALUES (?, ?, ?)',
                ['John', 'Doe', 'john.doe@example.com']
            );
            await client.commit();
            console.log('Transaction committed');
        } catch (error) {
            await client.rollback();
            console.error('Transaction rolled back:', error.message);
        }

        // Disconnect
        await client.disconnect();
        console.log('Disconnected');

    } catch (error) {
        console.error('Error:', error.message);
        try { await client.disconnect(); } catch (_) {}
    }
}

if (require.main === module) {
    example();
}

module.exports = InformixClient;
