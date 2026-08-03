/**
 * TypeScript gRPC client for informix-proxy
 *
 * Typed Node.js client for the InformixService gRPC API defined in
 * ../proto/informix.proto (a copy of the canonical
 * src/main/proto/informix.proto in this repo, kept for reference).
 * Provides connect, disconnect, ping, query, queryStream, execute, batch,
 * transactions, metadata, and prepared statements.
 */

import * as grpc from '@grpc/grpc-js';
import * as protoLoader from '@grpc/proto-loader';
import protoDescriptor from './informix-descriptor.json';

// ---------------------------------------------------------------------------
// Proto loading
// ---------------------------------------------------------------------------

// Loaded from a precompiled protobufjs JSON descriptor (generated at build
// time by scripts/generate-proto.mjs from the canonical .proto) rather than
// read from disk at runtime. A runtime file read via __dirname or
// import.meta.url breaks the moment a consumer's bundler (webpack, esbuild,
// ...) inlines this package into its own output - __dirname then resolves
// to the bundle's own directory, not this package's real location on disk.
// The descriptor is a plain JSON import, so bundlers handle it the same way
// regardless of target (ESM, CJS, or bundled into someone else's server
// build).
const packageDefinition = protoLoader.fromJSON(protoDescriptor as any, {
  keepCase: true,
  longs: String,
  enums: String,
  defaults: true,
  oneofs: true,
});
const informixProto = grpc.loadPackageDefinition(packageDefinition).informix as any;

// The RPC error codes that mean the connection itself is dead, not just the
// one call - every method that takes a connection_id resets on these so a
// caller who reconnects and retries always has a fresh, valid connection_id
// rather than one the proxy has already discarded.
const TRANSIENT_CODES = new Set([grpc.status.UNAVAILABLE, grpc.status.INTERNAL]);

// ---------------------------------------------------------------------------
// Public types
// ---------------------------------------------------------------------------

export interface InformixConnectionConfig {
  host: string;
  port: number;
  database: string;
  username: string;
  password: string;
  server?: string;
  properties?: Record<string, string>;
  poolSize?: number;
  /**
   * Maximum time in milliseconds to wait for the Connect RPC to complete.
   * Defaults to 10 000 ms.
   */
  connectTimeoutMs?: number;
}

export interface ConnectionInfo {
  connectionId: string;
  serverVersion: string;
}

export interface PingResult {
  alive: boolean;
  latencyMs: number;
}

export interface ColumnInfo {
  name: string;
  type: string;
  precision: number;
  scale: number;
  nullable: boolean;
}

export interface QueryResult {
  rows: Record<string, any>[];
  columns: ColumnInfo[] | null;
  rowCount: number;
}

export interface StreamResult {
  columns: ColumnInfo[] | null;
  rowCount: number;
}

export interface QueryOptions {
  fetchSize?: number;
  maxRows?: number;
}

export interface TableInfo {
  name: string;
  schema: string;
  type: string;
  columns: ColumnInfo[];
}

export interface PreparedStatementInfo {
  statementId: string;
  parameterCount: number;
}

export type ParameterValue =
  | string
  | number
  | boolean
  | Buffer
  | null
  | undefined;

export type IsolationLevel =
  | 'READ_UNCOMMITTED'
  | 'READ_COMMITTED'
  | 'REPEATABLE_READ'
  | 'SERIALIZABLE';

export interface InformixClientLogger {
  info(message: string, meta?: Record<string, unknown>): void;
  warn(message: string, meta?: Record<string, unknown>): void;
  error(message: string, meta?: Record<string, unknown>): void;
}

const noopLogger: InformixClientLogger = {
  info: () => {},
  warn: () => {},
  error: () => {},
};

// ---------------------------------------------------------------------------
// Client options
// ---------------------------------------------------------------------------

export interface InformixClientOptions {
  /** gRPC keepalive interval in ms (default: 30 000) */
  keepaliveTimeMs?: number;
  /** gRPC keepalive timeout in ms (default: 10 000) */
  keepaliveTimeoutMs?: number;
  /** Allow keepalive pings without active RPCs (default: true) */
  keepalivePermitWithoutCalls?: boolean;
  /** Max inbound message size in bytes (default: 64 MB) */
  maxReceiveMessageLength?: number;
  /** Structured logger for connection lifecycle events (default: no-op) */
  logger?: InformixClientLogger;
}

// ---------------------------------------------------------------------------
// InformixClient
// ---------------------------------------------------------------------------

export class InformixClient {
  private client: any;
  private connectionId: string | null = null;
  private _serverVersion: string | null = null;
  private logger: InformixClientLogger;

  constructor(
    proxyHost = 'localhost',
    proxyPort = 50051,
    options: InformixClientOptions = {},
  ) {
    const target = `${proxyHost}:${proxyPort}`;
    this.logger = options.logger ?? noopLogger;
    this.client = new informixProto.InformixService(
      target,
      grpc.credentials.createInsecure(),
      {
        'grpc.keepalive_time_ms': options.keepaliveTimeMs ?? 30_000,
        'grpc.keepalive_timeout_ms': options.keepaliveTimeoutMs ?? 10_000,
        'grpc.keepalive_permit_without_calls':
          (options.keepalivePermitWithoutCalls ?? true) ? 1 : 0,
        'grpc.max_receive_message_length':
          options.maxReceiveMessageLength ?? 64 * 1024 * 1024,
      },
    );
  }

  /** Whether a connection has been established. */
  get isConnected(): boolean {
    return this.connectionId !== null;
  }

  /** Server version string (available after connect). */
  get serverVersion(): string | null {
    return this._serverVersion;
  }

  /** The active connection id (or null). */
  get activeConnectionId(): string | null {
    return this.connectionId;
  }

  // -----------------------------------------------------------------------
  // Connection management
  // -----------------------------------------------------------------------

  async connect(config: InformixConnectionConfig): Promise<ConnectionInfo> {
    const properties: Record<string, string> = { ...(config.properties ?? {}) };
    if (config.server) {
      properties['INFORMIXSERVER'] = config.server;
    }

    const timeoutMs = config.connectTimeoutMs ?? 10_000;
    const deadline = new Date(Date.now() + timeoutMs);

    return new Promise<ConnectionInfo>((resolve, reject) => {
      this.client.Connect(
        {
          host: config.host,
          port: config.port,
          database: config.database,
          username: config.username,
          password: config.password,
          properties,
          pool_size: config.poolSize ?? 10,
        },
        { deadline },
        (error: grpc.ServiceError | null, response: any) => {
          if (error) {
            // DEADLINE_EXCEEDED is the gRPC status for a timed-out unary call.
            if (error.code === grpc.status.DEADLINE_EXCEEDED) {
              return reject(
                new Error(
                  `Informix Connect timed out after ${timeoutMs} ms ` +
                    `(${config.host}:${config.port}/${config.database}).`
                )
              );
            }
            return reject(error);
          }
          if (!response.success) return reject(new Error(response.error || 'Connection failed'));
          // A second connect() racing ahead of an earlier one on the same
          // instance would otherwise silently overwrite connectionId and
          // orphan the previous pool - release it first if that happened.
          if (this.connectionId && this.connectionId !== response.connection_id) {
            this.logger.warn('connect: overwriting a still-active connection', {
              previousConnectionId: this.connectionId,
              newConnectionId: response.connection_id,
            });
            this._releaseConnectionId(this.connectionId);
          }
          this.connectionId = response.connection_id;
          this._serverVersion = response.server_version;
          resolve({
            connectionId: response.connection_id,
            serverVersion: response.server_version,
          });
        },
      );
    });
  }

  async disconnect(): Promise<void> {
    if (!this.connectionId) return;
    const connectionId = this.connectionId;
    this.connectionId = null;
    return new Promise<void>((resolve, reject) => {
      this.client.Disconnect(
        { connection_id: connectionId },
        (error: grpc.ServiceError | null) => {
          if (error) {
            this.logger.warn('disconnect: Disconnect RPC failed', { connectionId, error: String(error) });
            return reject(error);
          }
          this.logger.info('disconnect: released connection', { connectionId });
          resolve();
        },
      );
    });
  }

  async ping(): Promise<PingResult> {
    this._checkConnection();
    return new Promise<PingResult>((resolve, reject) => {
      this.client.Ping(
        { connection_id: this.connectionId },
        (error: grpc.ServiceError | null, response: any) => {
          if (error) return reject(this._maybeReset(error));
          resolve({ alive: response.alive, latencyMs: response.latency_ms });
        },
      );
    });
  }

  /**
   * Mark the connection as dead so the caller can reconnect.
   *
   * Best-effort releases the server-side pool for the abandoned connection
   * id (fire-and-forget, short deadline) - otherwise the proxy keeps that
   * pool open forever since no one holds the connection_id anymore.
   */
  resetConnection(reason = 'unknown'): void {
    const staleId = this.connectionId;
    this.connectionId = null;
    if (!staleId) return;
    this.logger.warn('resetConnection: releasing stale connection', { connectionId: staleId, reason });
    this._releaseConnectionId(staleId);
  }

  private _releaseConnectionId(connectionId: string): void {
    this.client.Disconnect(
      { connection_id: connectionId },
      { deadline: new Date(Date.now() + 2_000) },
      () => {},
    );
  }

  /** Reset the connection if `error` indicates the transport/session itself died. */
  private _maybeReset(error: grpc.ServiceError): grpc.ServiceError {
    if (TRANSIENT_CODES.has(error.code)) {
      this.resetConnection(`grpc:${grpc.status[error.code]}`);
    }
    return error;
  }

  // -----------------------------------------------------------------------
  // Query execution
  // -----------------------------------------------------------------------

  async query(
    sql: string,
    params: ParameterValue[] = [],
    options: QueryOptions = {},
  ): Promise<QueryResult> {
    this._checkConnection();
    return new Promise<QueryResult>((resolve, reject) => {
      const request = {
        connection_id: this.connectionId,
        sql,
        parameters: this._convertParameters(params),
        fetch_size: options.fetchSize ?? 100,
        max_rows: options.maxRows ?? 0,
      };

      const rows: Record<string, any>[] = [];
      let columns: ColumnInfo[] | null = null;
      let totalRows = 0;
      const call = this.client.ExecuteQuery(request);

      call.on('data', (response: any) => {
        if (response.error) return reject(new Error(response.error));
        if (response.columns?.length > 0) columns = response.columns;
        if (response.rows && columns) {
          for (const row of response.rows) {
            const obj: Record<string, any> = {};
            row.values.forEach((value: any, index: number) => {
              obj[columns![index]!.name] = this._convertValue(value);
            });
            rows.push(obj);
          }
        }
        totalRows = response.total_rows;
      });

      call.on('end', () => resolve({ rows, columns, rowCount: totalRows }));
      call.on('error', (error: grpc.ServiceError) => reject(this._maybeReset(error)));
    });
  }

  async queryStream(
    sql: string,
    params: ParameterValue[] = [],
    onRow: (row: Record<string, any>) => void,
    options: QueryOptions = {},
  ): Promise<StreamResult> {
    this._checkConnection();
    return new Promise<StreamResult>((resolve, reject) => {
      const request = {
        connection_id: this.connectionId,
        sql,
        parameters: this._convertParameters(params),
        fetch_size: options.fetchSize ?? 100,
        max_rows: options.maxRows ?? 0,
      };

      let columns: ColumnInfo[] | null = null;
      let totalRows = 0;
      const call = this.client.ExecuteQuery(request);

      call.on('data', (response: any) => {
        if (response.error) return reject(new Error(response.error));
        if (response.columns?.length > 0) columns = response.columns;
        if (response.rows && columns) {
          for (const row of response.rows) {
            const obj: Record<string, any> = {};
            row.values.forEach((value: any, index: number) => {
              obj[columns![index]!.name] = this._convertValue(value);
            });
            onRow(obj);
          }
        }
        totalRows = response.total_rows;
      });

      call.on('end', () => resolve({ columns, rowCount: totalRows }));
      call.on('error', (error: grpc.ServiceError) => reject(this._maybeReset(error)));
    });
  }

  // -----------------------------------------------------------------------
  // Execute (INSERT / UPDATE / DELETE)
  // -----------------------------------------------------------------------

  async execute(sql: string, params: ParameterValue[] = []): Promise<number> {
    this._checkConnection();
    return new Promise<number>((resolve, reject) => {
      this.client.ExecuteUpdate(
        {
          connection_id: this.connectionId,
          sql,
          parameters: this._convertParameters(params),
        },
        (error: grpc.ServiceError | null, response: any) => {
          if (error) return reject(this._maybeReset(error));
          if (response.error) return reject(new Error(response.error));
          resolve(response.rows_affected);
        },
      );
    });
  }

  // -----------------------------------------------------------------------
  // Batch
  // -----------------------------------------------------------------------

  async batch(sqlStatements: string[]): Promise<number[]> {
    this._checkConnection();
    return new Promise<number[]>((resolve, reject) => {
      this.client.ExecuteBatch(
        {
          connection_id: this.connectionId,
          sql_statements: sqlStatements,
        },
        (error: grpc.ServiceError | null, response: any) => {
          if (error) return reject(this._maybeReset(error));
          if (response.error) return reject(new Error(response.error));
          resolve(response.rows_affected);
        },
      );
    });
  }

  // -----------------------------------------------------------------------
  // Transactions
  // -----------------------------------------------------------------------

  async beginTransaction(isolationLevel: IsolationLevel = 'READ_COMMITTED'): Promise<void> {
    this._checkConnection();
    return new Promise<void>((resolve, reject) => {
      this.client.BeginTransaction(
        { connection_id: this.connectionId, isolation_level: isolationLevel },
        (error: grpc.ServiceError | null, response: any) => {
          if (error) return reject(this._maybeReset(error));
          if (!response.success) return reject(new Error(response.error));
          resolve();
        },
      );
    });
  }

  async commit(): Promise<void> {
    this._checkConnection();
    return new Promise<void>((resolve, reject) => {
      this.client.Commit(
        { connection_id: this.connectionId },
        (error: grpc.ServiceError | null, response: any) => {
          if (error) return reject(this._maybeReset(error));
          if (!response.success) return reject(new Error(response.error));
          resolve();
        },
      );
    });
  }

  async rollback(): Promise<void> {
    this._checkConnection();
    return new Promise<void>((resolve, reject) => {
      this.client.Rollback(
        { connection_id: this.connectionId },
        (error: grpc.ServiceError | null, response: any) => {
          if (error) return reject(this._maybeReset(error));
          if (!response.success) return reject(new Error(response.error));
          resolve();
        },
      );
    });
  }

  // -----------------------------------------------------------------------
  // Prepared statements
  // -----------------------------------------------------------------------

  async prepareStatement(sql: string): Promise<PreparedStatementInfo> {
    this._checkConnection();
    return new Promise<PreparedStatementInfo>((resolve, reject) => {
      this.client.PrepareStatement(
        { connection_id: this.connectionId, sql },
        (error: grpc.ServiceError | null, response: any) => {
          if (error) return reject(this._maybeReset(error));
          if (response.error) return reject(new Error(response.error));
          resolve({
            statementId: response.statement_id,
            parameterCount: response.parameter_count,
          });
        },
      );
    });
  }

  async executePrepared(
    statementId: string,
    params: ParameterValue[] = [],
    options: Pick<QueryOptions, 'fetchSize'> = {},
  ): Promise<QueryResult> {
    this._checkConnection();
    return new Promise<QueryResult>((resolve, reject) => {
      const request = {
        connection_id: this.connectionId,
        statement_id: statementId,
        parameters: this._convertParameters(params),
        fetch_size: options.fetchSize ?? 100,
      };

      const rows: Record<string, any>[] = [];
      let columns: ColumnInfo[] | null = null;
      let totalRows = 0;
      const call = this.client.ExecutePrepared(request);

      call.on('data', (response: any) => {
        if (response.error) return reject(new Error(response.error));
        if (response.columns?.length > 0) columns = response.columns;
        if (response.rows && columns) {
          for (const row of response.rows) {
            const obj: Record<string, any> = {};
            row.values.forEach((value: any, index: number) => {
              obj[columns![index]!.name] = this._convertValue(value);
            });
            rows.push(obj);
          }
        }
        totalRows = response.total_rows;
      });

      call.on('end', () => resolve({ rows, columns, rowCount: totalRows }));
      call.on('error', (error: grpc.ServiceError) => reject(this._maybeReset(error)));
    });
  }

  async closePrepared(statementId: string): Promise<void> {
    this._checkConnection();
    return new Promise<void>((resolve, reject) => {
      this.client.ClosePrepared(
        { connection_id: this.connectionId, statement_id: statementId },
        (error: grpc.ServiceError | null, response: any) => {
          if (error) return reject(this._maybeReset(error));
          if (!response.success) return reject(new Error('Failed to close prepared statement'));
          resolve();
        },
      );
    });
  }

  // -----------------------------------------------------------------------
  // Metadata
  // -----------------------------------------------------------------------

  async getMetadata(tableName = ''): Promise<TableInfo[]> {
    this._checkConnection();
    return new Promise<TableInfo[]>((resolve, reject) => {
      this.client.GetMetadata(
        { connection_id: this.connectionId, table_name: tableName },
        (error: grpc.ServiceError | null, response: any) => {
          if (error) return reject(this._maybeReset(error));
          if (response.error) return reject(new Error(response.error));
          resolve(response.tables);
        },
      );
    });
  }

  // -----------------------------------------------------------------------
  // Internal helpers
  // -----------------------------------------------------------------------

  private _checkConnection(): void {
    if (!this.connectionId) {
      throw new Error('Not connected to database. Call connect() first.');
    }
  }

  private _convertParameters(params: ParameterValue[]): any[] {
    return params.map((param) => {
      if (param === null || param === undefined) return { is_null: true };
      if (typeof param === 'string') return { string_value: param };
      if (typeof param === 'number')
        return Number.isInteger(param)
          ? { int_value: param }
          : { double_value: param };
      if (typeof param === 'boolean') return { bool_value: param };
      if (Buffer.isBuffer(param)) return { bytes_value: param };
      return { string_value: String(param) };
    });
  }

  private _convertValue(value: any): any {
    if (value.is_null) return null;
    if (value.string_data !== undefined && value.string_data !== '')
      return value.string_data;
    if (value.int_data !== undefined && value.int_data !== 0)
      return value.int_data;
    if (value.long_data !== undefined && value.long_data !== '0')
      return parseInt(value.long_data, 10);
    if (value.double_data !== undefined && value.double_data !== 0)
      return value.double_data;
    if (value.bool_data !== undefined) return value.bool_data;
    if (value.bytes_data !== undefined && value.bytes_data.length > 0)
      return Buffer.from(value.bytes_data);
    // gRPC defaults numeric 0 and empty string - if not null, return string_data
    if (!value.is_null && value.string_data !== undefined)
      return value.string_data;
    return null;
  }
}

export default InformixClient;
