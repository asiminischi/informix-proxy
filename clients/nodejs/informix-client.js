/**
 * Informix gRPC Client for Node.js
 *
 * High-performance client that connects to Informix via gRPC proxy
 *
 * Features:
 * - Automatic connection management
 * - Streaming large result sets
 * - Transaction support
 * - Prepared statements
 * - Connection pooling at proxy level
 */

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

    /**
     * Connect to Informix database
     * @param {Object} config - Database configuration
     * @param {string} config.host - Database host
     * @param {number} config.port - Database port
     * @param {string} config.database - Database name
     * @param {string} config.username - Username
     * @param {string} config.password - Password
     * @param {Object} config.properties - Additional JDBC properties
     * @param {number} config.poolSize - Connection pool size
     * @returns {Promise<Object>} Connection info
     */
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
                if (error) {
                    reject(error);
                    return;
                }

                if (!response.success) {
                    reject(new Error(response.error));
                    return;
                }

                this.connectionId = response.connection_id;
                resolve({
                    connectionId: response.connection_id,
                    serverVersion: response.server_version
                });
            });
        });
    }

    /**
     * Disconnect from database
     * @returns {Promise<void>}
     */
    async disconnect() {
        if (!this.connectionId) {
            return;
        }

        return new Promise((resolve, reject) => {
            this.client.Disconnect({ connection_id: this.connectionId }, (error) => {
                if (error) {
                    reject(error);
                    return;
                }
                this.connectionId = null;
                resolve();
            });
        });
    }

    /**
     * Test connection
     * @returns {Promise<Object>} Ping result
     */
    async ping() {
        this._checkConnection();

        return new Promise((resolve, reject) => {
            this.client.Ping({ connection_id: this.connectionId }, (error, response) => {
                if (error) {
                    reject(error);
                    return;
                }
                resolve({
                    alive: response.alive,
                    latencyMs: response.latency_ms
                });
            });
        });
    }

    /**
     * Execute a query and get all results
     * @param {string} sql - SQL query
     * @param {Array} params - Query parameters
     * @param {Object} options - Query options
     * @returns {Promise<Object>} Query results
     */
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
                if (response.error) {
                    reject(new Error(response.error));
                    return;
                }

                // Save column metadata from first response
                if (response.columns && response.columns.length > 0) {
                    columns = response.columns;
                }

                // Convert rows to objects
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

            call.on('end', () => {
                resolve({
                    rows: rows,
                    columns: columns,
                    rowCount: totalRows
                });
            });

            call.on('error', (error) => {
                reject(error);
            });
        });
    }

    /**
     * Execute a query with streaming results
     * Useful for large result sets
     * @param {string} sql - SQL query
     * @param {Array} params - Query parameters
     * @param {Function} onRow - Callback for each row
     * @param {Object} options - Query options
     * @returns {Promise<Object>} Metadata
     */
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
                if (response.error) {
                    reject(new Error(response.error));
                    return;
                }

                if (response.columns && response.columns.length > 0) {
                    columns = response.columns;
                }

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

            call.on('end', () => {
                resolve({
                    columns: columns,
                    rowCount: totalRows
                });
            });

            call.on('error', (error) => {
                reject(error);
            });
        });
    }

    /**
     * Execute an UPDATE/INSERT/DELETE statement
     * @param {string} sql - SQL statement
     * @param {Array} params - Statement parameters
     * @returns {Promise<number>} Rows affected
     */
    async execute(sql, params = []) {
        this._checkConnection();

        return new Promise((resolve, reject) => {
            const request = {
                connection_id: this.connectionId,
                sql: sql,
                parameters: this._convertParameters(params)
            };

            this.client.ExecuteUpdate(request, (error, response) => {
                if (error) {
                    reject(error);
                    return;
                }

                if (response.error) {
                    reject(new Error(response.error));
                    return;
                }

                resolve(response.rows_affected);
            });
        });
    }

    /**
     * Execute multiple statements in a batch
     * @param {Array<string>} sqlStatements - Array of SQL statements
     * @returns {Promise<Array<number>>} Rows affected for each statement
     */
    async batch(sqlStatements) {
        this._checkConnection();

        return new Promise((resolve, reject) => {
            const request = {
                connection_id: this.connectionId,
                sql_statements: sqlStatements
            };

            this.client.ExecuteBatch(request, (error, response) => {
                if (error) {
                    reject(error);
                    return;
                }

                if (response.error) {
                    reject(new Error(response.error));
                    return;
                }

                resolve(response.rows_affected);
            });
        });
    }

    /**
     * Begin a transaction
     * @param {string} isolationLevel - Transaction isolation level
     * @returns {Promise<void>}
     */
    async beginTransaction(isolationLevel = 'READ_COMMITTED') {
        this._checkConnection();

        return new Promise((resolve, reject) => {
            const request = {
                connection_id: this.connectionId,
                isolation_level: isolationLevel
            };

            this.client.BeginTransaction(request, (error, response) => {
                if (error) {
                    reject(error);
                    return;
                }

                if (!response.success) {
                    reject(new Error(response.error));
                    return;
                }

                resolve();
            });
        });
    }

    /**
     * Commit current transaction
     * @returns {Promise<void>}
     */
    async commit() {
        this._checkConnection();

        return new Promise((resolve, reject) => {
            this.client.Commit({ connection_id: this.connectionId }, (error, response) => {
                if (error) {
                    reject(error);
                    return;
                }

                if (!response.success) {
                    reject(new Error(response.error));
                    return;
                }

                resolve();
            });
        });
    }

    /**
     * Rollback current transaction
     * @returns {Promise<void>}
     */
    async rollback() {
        this._checkConnection();

        return new Promise((resolve, reject) => {
            this.client.Rollback({ connection_id: this.connectionId }, (error, response) => {
                if (error) {
                    reject(error);
                    return;
                }

                if (!response.success) {
                    reject(new Error(response.error));
                    return;
                }

                resolve();
            });
        });
    }

    /**
     * Get database metadata
     * @param {string} tableName - Optional table name
     * @returns {Promise<Array>} Table metadata
     */
    async getMetadata(tableName = '') {
        this._checkConnection();

        return new Promise((resolve, reject) => {
            const request = {
                connection_id: this.connectionId,
                table_name: tableName
            };

            this.client.GetMetadata(request, (error, response) => {
                if (error) {
                    reject(error);
                    return;
                }

                if (response.error) {
                    reject(new Error(response.error));
                    return;
                }

                resolve(response.tables);
            });
        });
    }

    // Helper methods

    _checkConnection() {
        if (!this.connectionId) {
            throw new Error('Not connected to database. Call connect() first.');
        }
    }

    _convertParameters(params) {
        return params.map(param => {
            if (param === null || param === undefined) {
                return { is_null: true };
            }

            if (typeof param === 'string') {
                return { string_value: param };
            } else if (typeof param === 'number') {
                if (Number.isInteger(param)) {
                    return { int_value: param };
                } else {
                    return { double_value: param };
                }
            } else if (typeof param === 'boolean') {
                return { bool_value: param };
            } else if (Buffer.isBuffer(param)) {
                return { bytes_value: param };
            } else {
                return { string_value: String(param) };
            }
        });
    }

    _convertValue(value) {
        if (value.is_null) {
            return null;
        }

        if (value.string_data !== undefined && value.string_data !== '') {
            return value.string_data;
        }
        if (value.int_data !== undefined) {
            return value.int_data;
        }
        if (value.long_data !== undefined) {
            return parseInt(value.long_data);
        }
        if (value.double_data !== undefined) {
            return value.double_data;
        }
        if (value.bool_data !== undefined) {
            return value.bool_data;
        }
        if (value.bytes_data !== undefined) {
            return Buffer.from(value.bytes_data);
        }

        return null;
    }
}

// Example usage
async function example() {
    const client = new InformixClient('localhost', 50051);

    try {
        // Connect
        const connInfo = await client.connect({
            host: 'informix-db',
            port: 9088,
            database: 'stores_demo',
            username: 'informix',
            password: 'in4mix',
            poolSize: 10
        });
        console.log('Connected:', connInfo.serverVersion);

        // Simple query
        const result = await client.query('SELECT * FROM customer WHERE customer_num < ?', [105]);
        console.log('Customers:', result.rows);

        // Streaming large results
        console.log('Streaming all customers:');
        await client.queryStream(
            'SELECT * FROM customer',
            [],
            (row) => {
                console.log(' -', row.fname, row.lname);
            },
            { fetchSize: 50 }
        );

        // Transaction example
        await client.beginTransaction();
        try {
            await client.execute(
                'INSERT INTO customer (customer_num, fname, lname) VALUES (?, ?, ?)',
                [999, 'John', 'Doe']
            );
            await client.execute(
                'UPDATE customer SET lname = ? WHERE customer_num = ?',
                ['Smith', 999]
            );
            await client.commit();
            console.log('Transaction committed');
        } catch (error) {
            await client.rollback();
            console.error('Transaction rolled back:', error.message);
        }

        // Batch operations
        const batchResults = await client.batch([
            'UPDATE customer SET company = \'ACME\' WHERE customer_num = 101',
            'UPDATE customer SET company = \'ACME\' WHERE customer_num = 102'
        ]);
        console.log('Batch results:', batchResults);

        // Get metadata
        const metadata = await client.getMetadata('customer');
        console.log('Customer table columns:', metadata[0].columns.map(c => c.name));

        // Disconnect
        await client.disconnect();
        console.log('Disconnected');

    } catch (error) {
        console.error('Error:', error.message);
        await client.disconnect();
    }
}

// Run example if called directly
if (require.main === module) {
    example();
}

module.exports = InformixClient;