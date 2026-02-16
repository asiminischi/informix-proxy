package com.informix.grpc;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.stub.StreamObserver;

import java.sql.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * High-performance gRPC proxy for Informix databases
 *
 * Features:
 * - Connection pooling with HikariCP
 * - Streaming large result sets
 * - Prepared statement caching
 * - Proper transaction management
 * - Thread-safe operations
 */
public class InformixProxyServer extends InformixServiceGrpc.InformixServiceImplBase {

    private final Map<String, HikariDataSource> connectionPools = new ConcurrentHashMap<>();
    private final Map<String, PreparedStatement> preparedStatements = new ConcurrentHashMap<>();
    private final AtomicLong connectionIdCounter = new AtomicLong(0);
    private final AtomicLong statementIdCounter = new AtomicLong(0);

    // Connection ID -> Connection mapping for transaction support
    private final Map<String, Connection> activeConnections = new ConcurrentHashMap<>();

    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(System.getenv().getOrDefault("GRPC_PORT", "50051"));

        Server server = ServerBuilder.forPort(port)
                .addService(new InformixProxyServer())
                .maxInboundMessageSize(50 * 1024 * 1024) // 50MB max message
                .build()
                .start();

        System.out.println("Informix gRPC Proxy started on port " + port);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("Shutting down gRPC server...");
            server.shutdown();
            try {
                server.awaitTermination(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }));

        server.awaitTermination();
    }

    @Override
    public void connect(ConnectionRequest request, StreamObserver<ConnectionResponse> responseObserver) {
        try {
            String connectionId = "conn_" + connectionIdCounter.incrementAndGet();

            // Build JDBC URL
            String jdbcUrl = String.format(
                    "jdbc:informix-sqli://%s:%d/%s",
                    request.getHost(),
                    request.getPort(),
                    request.getDatabase()
            );

            // Add additional properties to URL
            if (!request.getPropertiesMap().isEmpty()) {
                StringBuilder urlBuilder = new StringBuilder(jdbcUrl);
                for (Map.Entry<String, String> entry : request.getPropertiesMap().entrySet()) {
                    urlBuilder.append(";").append(entry.getKey()).append("=").append(entry.getValue());
                }
                jdbcUrl = urlBuilder.toString();
            }

            // Configure HikariCP connection pool
            HikariConfig config = new HikariConfig();
            config.setJdbcUrl(jdbcUrl);
            config.setUsername(request.getUsername());
            config.setPassword(request.getPassword());
            config.setDriverClassName("com.informix.jdbc.IfxDriver");

            // Pool configuration
            int poolSize = request.getPoolSize() > 0 ? request.getPoolSize() : 10;
            config.setMaximumPoolSize(poolSize);
            config.setMinimumIdle(2);
            config.setConnectionTimeout(30000); // 30 seconds
            config.setIdleTimeout(600000); // 10 minutes
            config.setMaxLifetime(1800000); // 30 minutes
            config.setConnectionTestQuery("SELECT 1 FROM systables WHERE tabid = 1");

            // Create pool
            HikariDataSource dataSource = new HikariDataSource(config);

            // Test connection
            String serverVersion = null;
            try (Connection conn = dataSource.getConnection()) {
                DatabaseMetaData meta = conn.getMetaData();
                serverVersion = meta.getDatabaseProductName() + " " + meta.getDatabaseProductVersion();
            }

            connectionPools.put(connectionId, dataSource);

            ConnectionResponse response = ConnectionResponse.newBuilder()
                    .setConnectionId(connectionId)
                    .setServerVersion(serverVersion)
                    .setSuccess(true)
                    .build();

            responseObserver.onNext(response);
            responseObserver.onCompleted();

        } catch (Exception e) {
            ConnectionResponse response = ConnectionResponse.newBuilder()
                    .setSuccess(false)
                    .setError(e.getMessage())
                    .build();
            responseObserver.onNext(response);
            responseObserver.onCompleted();
        }
    }

    @Override
    public void disconnect(DisconnectRequest request, StreamObserver<DisconnectResponse> responseObserver) {
        try {
            HikariDataSource dataSource = connectionPools.remove(request.getConnectionId());

            if (dataSource != null) {
                dataSource.close();
            }

            // Clean up any active connection
            Connection conn = activeConnections.remove(request.getConnectionId());
            if (conn != null && !conn.isClosed()) {
                conn.close();
            }

            DisconnectResponse response = DisconnectResponse.newBuilder()
                    .setSuccess(true)
                    .build();

            responseObserver.onNext(response);
            responseObserver.onCompleted();

        } catch (Exception e) {
            DisconnectResponse response = DisconnectResponse.newBuilder()
                    .setSuccess(false)
                    .build();
            responseObserver.onNext(response);
            responseObserver.onCompleted();
        }
    }

    @Override
    public void ping(PingRequest request, StreamObserver<PingResponse> responseObserver) {
        long startTime = System.currentTimeMillis();

        try {
            HikariDataSource dataSource = connectionPools.get(request.getConnectionId());

            if (dataSource == null) {
                throw new SQLException("Connection not found");
            }

            try (Connection conn = dataSource.getConnection();
                 Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT 1 FROM systables WHERE tabid = 1")) {
                rs.next();
            }

            long latency = System.currentTimeMillis() - startTime;

            PingResponse response = PingResponse.newBuilder()
                    .setAlive(true)
                    .setLatencyMs(latency)
                    .build();

            responseObserver.onNext(response);
            responseObserver.onCompleted();

        } catch (Exception e) {
            PingResponse response = PingResponse.newBuilder()
                    .setAlive(false)
                    .setLatencyMs(-1)
                    .build();
            responseObserver.onNext(response);
            responseObserver.onCompleted();
        }
    }

    @Override
    public void executeQuery(QueryRequest request, StreamObserver<QueryResponse> responseObserver) {
        try {
            HikariDataSource dataSource = connectionPools.get(request.getConnectionId());
            if (dataSource == null) {
                throw new SQLException("Connection not found");
            }

            Connection conn = activeConnections.get(request.getConnectionId());
            boolean closeConnection = false;

            if (conn == null) {
                conn = dataSource.getConnection();
                closeConnection = true;
            }

            try (PreparedStatement pstmt = conn.prepareStatement(request.getSql())) {

                // Set parameters
                setParameters(pstmt, request.getParametersList());

                // Set fetch size for streaming
                int fetchSize = request.getFetchSize() > 0 ? request.getFetchSize() : 100;
                pstmt.setFetchSize(fetchSize);

                if (request.getMaxRows() > 0) {
                    pstmt.setMaxRows(request.getMaxRows());
                }

                try (ResultSet rs = pstmt.executeQuery()) {
                    ResultSetMetaData meta = rs.getMetaData();
                    int columnCount = meta.getColumnCount();

                    // Build column metadata (sent once)
                    List<ColumnMetadata> columns = new ArrayList<>();
                    for (int i = 1; i <= columnCount; i++) {
                        columns.add(ColumnMetadata.newBuilder()
                                .setName(meta.getColumnName(i))
                                .setType(meta.getColumnTypeName(i))
                                .setPrecision(meta.getPrecision(i))
                                .setScale(meta.getScale(i))
                                .setNullable(meta.isNullable(i) == ResultSetMetaData.columnNullable)
                                .build());
                    }

                    // Stream results in chunks
                    List<Row> rowBatch = new ArrayList<>();
                    int totalRows = 0;

                    while (rs.next()) {
                        Row.Builder rowBuilder = Row.newBuilder();

                        for (int i = 1; i <= columnCount; i++) {
                            rowBuilder.addValues(convertValue(rs, i, meta.getColumnType(i)));
                        }

                        rowBatch.add(rowBuilder.build());
                        totalRows++;

                        // Send batch when it reaches fetch size
                        if (rowBatch.size() >= fetchSize) {
                            QueryResponse.Builder responseBuilder = QueryResponse.newBuilder()
                                    .addAllRows(rowBatch)
                                    .setHasMore(true)
                                    .setTotalRows(totalRows);

                            // Only send columns in first batch
                            if (totalRows == rowBatch.size()) {
                                responseBuilder.addAllColumns(columns);
                            }

                            responseObserver.onNext(responseBuilder.build());
                            rowBatch.clear();
                        }
                    }

                    // Send remaining rows
                    QueryResponse.Builder finalResponse = QueryResponse.newBuilder()
                            .addAllRows(rowBatch)
                            .setHasMore(false)
                            .setTotalRows(totalRows);

                    if (totalRows == rowBatch.size()) {
                        finalResponse.addAllColumns(columns);
                    }

                    responseObserver.onNext(finalResponse.build());
                    responseObserver.onCompleted();
                }
            } finally {
                if (closeConnection && conn != null) {
                    conn.close();
                }
            }

        } catch (Exception e) {
            QueryResponse error = QueryResponse.newBuilder()
                    .setError(e.getMessage())
                    .build();
            responseObserver.onNext(error);
            responseObserver.onCompleted();
        }
    }

    @Override
    public void executeUpdate(UpdateRequest request, StreamObserver<UpdateResponse> responseObserver) {
        try {
            HikariDataSource dataSource = connectionPools.get(request.getConnectionId());
            if (dataSource == null) {
                throw new SQLException("Connection not found");
            }

            Connection conn = activeConnections.get(request.getConnectionId());
            boolean closeConnection = false;

            if (conn == null) {
                conn = dataSource.getConnection();
                closeConnection = true;
            }

            try (PreparedStatement pstmt = conn.prepareStatement(request.getSql())) {
                setParameters(pstmt, request.getParametersList());

                int rowsAffected = pstmt.executeUpdate();

                UpdateResponse response = UpdateResponse.newBuilder()
                        .setRowsAffected(rowsAffected)
                        .build();

                responseObserver.onNext(response);
                responseObserver.onCompleted();
            } finally {
                if (closeConnection && conn != null) {
                    conn.close();
                }
            }

        } catch (Exception e) {
            UpdateResponse response = UpdateResponse.newBuilder()
                    .setRowsAffected(-1)
                    .setError(e.getMessage())
                    .build();
            responseObserver.onNext(response);
            responseObserver.onCompleted();
        }
    }

    @Override
    public void executeBatch(BatchRequest request, StreamObserver<BatchResponse> responseObserver) {
        try {
            HikariDataSource dataSource = connectionPools.get(request.getConnectionId());
            if (dataSource == null) {
                throw new SQLException("Connection not found");
            }

            Connection conn = activeConnections.get(request.getConnectionId());
            boolean closeConnection = false;

            if (conn == null) {
                conn = dataSource.getConnection();
                closeConnection = true;
            }

            try (Statement stmt = conn.createStatement()) {
                for (String sql : request.getSqlStatementsList()) {
                    stmt.addBatch(sql);
                }

                int[] results = stmt.executeBatch();

                BatchResponse.Builder responseBuilder = BatchResponse.newBuilder();
                for (int result : results) {
                    responseBuilder.addRowsAffected(result);
                }

                responseObserver.onNext(responseBuilder.build());
                responseObserver.onCompleted();
            } finally {
                if (closeConnection && conn != null) {
                    conn.close();
                }
            }

        } catch (Exception e) {
            BatchResponse response = BatchResponse.newBuilder()
                    .setError(e.getMessage())
                    .build();
            responseObserver.onNext(response);
            responseObserver.onCompleted();
        }
    }

    @Override
    public void prepareStatement(PrepareRequest request, StreamObserver<PrepareResponse> responseObserver) {
        try {
            HikariDataSource dataSource = connectionPools.get(request.getConnectionId());
            if (dataSource == null) {
                throw new SQLException("Connection not found");
            }

            Connection conn = dataSource.getConnection();
            PreparedStatement pstmt = conn.prepareStatement(request.getSql());

            String statementId = "stmt_" + statementIdCounter.incrementAndGet();
            preparedStatements.put(statementId, pstmt);

            ParameterMetaData paramMeta = pstmt.getParameterMetaData();

            PrepareResponse response = PrepareResponse.newBuilder()
                    .setStatementId(statementId)
                    .setParameterCount(paramMeta.getParameterCount())
                    .build();

            responseObserver.onNext(response);
            responseObserver.onCompleted();

        } catch (Exception e) {
            PrepareResponse response = PrepareResponse.newBuilder()
                    .setError(e.getMessage())
                    .build();
            responseObserver.onNext(response);
            responseObserver.onCompleted();
        }
    }

    @Override
    public void executePrepared(ExecutePreparedRequest request, StreamObserver<QueryResponse> responseObserver) {
        try {
            PreparedStatement pstmt = preparedStatements.get(request.getStatementId());
            if (pstmt == null) {
                throw new SQLException("Prepared statement not found");
            }

            setParameters(pstmt, request.getParametersList());

            int fetchSize = request.getFetchSize() > 0 ? request.getFetchSize() : 100;
            pstmt.setFetchSize(fetchSize);

            try (ResultSet rs = pstmt.executeQuery()) {
                ResultSetMetaData meta = rs.getMetaData();
                int columnCount = meta.getColumnCount();

                List<ColumnMetadata> columns = new ArrayList<>();
                for (int i = 1; i <= columnCount; i++) {
                    columns.add(ColumnMetadata.newBuilder()
                            .setName(meta.getColumnName(i))
                            .setType(meta.getColumnTypeName(i))
                            .setPrecision(meta.getPrecision(i))
                            .setScale(meta.getScale(i))
                            .setNullable(meta.isNullable(i) == ResultSetMetaData.columnNullable)
                            .build());
                }

                List<Row> rowBatch = new ArrayList<>();
                int totalRows = 0;

                while (rs.next()) {
                    Row.Builder rowBuilder = Row.newBuilder();

                    for (int i = 1; i <= columnCount; i++) {
                        rowBuilder.addValues(convertValue(rs, i, meta.getColumnType(i)));
                    }

                    rowBatch.add(rowBuilder.build());
                    totalRows++;

                    if (rowBatch.size() >= fetchSize) {
                        QueryResponse.Builder responseBuilder = QueryResponse.newBuilder()
                                .addAllRows(rowBatch)
                                .setHasMore(true)
                                .setTotalRows(totalRows);

                        if (totalRows == rowBatch.size()) {
                            responseBuilder.addAllColumns(columns);
                        }

                        responseObserver.onNext(responseBuilder.build());
                        rowBatch.clear();
                    }
                }

                QueryResponse.Builder finalResponse = QueryResponse.newBuilder()
                        .addAllRows(rowBatch)
                        .setHasMore(false)
                        .setTotalRows(totalRows);

                if (totalRows == rowBatch.size()) {
                    finalResponse.addAllColumns(columns);
                }

                responseObserver.onNext(finalResponse.build());
                responseObserver.onCompleted();
            }

        } catch (Exception e) {
            QueryResponse error = QueryResponse.newBuilder()
                    .setError(e.getMessage())
                    .build();
            responseObserver.onNext(error);
            responseObserver.onCompleted();
        }
    }

    @Override
    public void closePrepared(ClosePreparedRequest request, StreamObserver<ClosePreparedResponse> responseObserver) {
        try {
            PreparedStatement pstmt = preparedStatements.remove(request.getStatementId());

            if (pstmt != null) {
                Connection conn = pstmt.getConnection();
                pstmt.close();
                if (conn != null && !conn.isClosed()) {
                    conn.close();
                }
            }

            ClosePreparedResponse response = ClosePreparedResponse.newBuilder()
                    .setSuccess(true)
                    .build();

            responseObserver.onNext(response);
            responseObserver.onCompleted();

        } catch (Exception e) {
            ClosePreparedResponse response = ClosePreparedResponse.newBuilder()
                    .setSuccess(false)
                    .build();
            responseObserver.onNext(response);
            responseObserver.onCompleted();
        }
    }

    @Override
    public void beginTransaction(TransactionRequest request, StreamObserver<TransactionResponse> responseObserver) {
        try {
            HikariDataSource dataSource = connectionPools.get(request.getConnectionId());
            if (dataSource == null) {
                throw new SQLException("Connection not found");
            }

            Connection conn = dataSource.getConnection();
            conn.setAutoCommit(false);

            // Set isolation level if specified
            if (!request.getIsolationLevel().isEmpty()) {
                switch (request.getIsolationLevel()) {
                    case "READ_UNCOMMITTED":
                        conn.setTransactionIsolation(Connection.TRANSACTION_READ_UNCOMMITTED);
                        break;
                    case "READ_COMMITTED":
                        conn.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
                        break;
                    case "REPEATABLE_READ":
                        conn.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
                        break;
                    case "SERIALIZABLE":
                        conn.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
                        break;
                }
            }

            activeConnections.put(request.getConnectionId(), conn);

            TransactionResponse response = TransactionResponse.newBuilder()
                    .setSuccess(true)
                    .build();

            responseObserver.onNext(response);
            responseObserver.onCompleted();

        } catch (Exception e) {
            TransactionResponse response = TransactionResponse.newBuilder()
                    .setSuccess(false)
                    .setError(e.getMessage())
                    .build();
            responseObserver.onNext(response);
            responseObserver.onCompleted();
        }
    }

    @Override
    public void commit(CommitRequest request, StreamObserver<CommitResponse> responseObserver) {
        try {
            Connection conn = activeConnections.get(request.getConnectionId());

            if (conn == null) {
                throw new SQLException("No active transaction");
            }

            conn.commit();
            conn.setAutoCommit(true);
            conn.close();
            activeConnections.remove(request.getConnectionId());

            CommitResponse response = CommitResponse.newBuilder()
                    .setSuccess(true)
                    .build();

            responseObserver.onNext(response);
            responseObserver.onCompleted();

        } catch (Exception e) {
            CommitResponse response = CommitResponse.newBuilder()
                    .setSuccess(false)
                    .setError(e.getMessage())
                    .build();
            responseObserver.onNext(response);
            responseObserver.onCompleted();
        }
    }

    @Override
    public void rollback(RollbackRequest request, StreamObserver<RollbackResponse> responseObserver) {
        try {
            Connection conn = activeConnections.get(request.getConnectionId());

            if (conn == null) {
                throw new SQLException("No active transaction");
            }

            conn.rollback();
            conn.setAutoCommit(true);
            conn.close();
            activeConnections.remove(request.getConnectionId());

            RollbackResponse response = RollbackResponse.newBuilder()
                    .setSuccess(true)
                    .build();

            responseObserver.onNext(response);
            responseObserver.onCompleted();

        } catch (Exception e) {
            RollbackResponse response = RollbackResponse.newBuilder()
                    .setSuccess(false)
                    .setError(e.getMessage())
                    .build();
            responseObserver.onNext(response);
            responseObserver.onCompleted();
        }
    }

    @Override
    public void getMetadata(MetadataRequest request, StreamObserver<MetadataResponse> responseObserver) {
        try {
            HikariDataSource dataSource = connectionPools.get(request.getConnectionId());
            if (dataSource == null) {
                throw new SQLException("Connection not found");
            }

            try (Connection conn = dataSource.getConnection()) {
                DatabaseMetaData meta = conn.getMetaData();

                List<TableInfo> tables = new ArrayList<>();

                if (request.getTableName().isEmpty()) {
                    // Get all tables
                    try (ResultSet rs = meta.getTables(null, null, "%", new String[]{"TABLE"})) {
                        while (rs.next()) {
                            String tableName = rs.getString("TABLE_NAME");
                            String schema = rs.getString("TABLE_SCHEM");

                            tables.add(TableInfo.newBuilder()
                                    .setName(tableName)
                                    .setSchema(schema != null ? schema : "")
                                    .setType("TABLE")
                                    .build());
                        }
                    }
                } else {
                    // Get specific table with columns
                    try (ResultSet rs = meta.getColumns(null, null, request.getTableName(), "%")) {
                        List<ColumnMetadata> columns = new ArrayList<>();

                        while (rs.next()) {
                            columns.add(ColumnMetadata.newBuilder()
                                    .setName(rs.getString("COLUMN_NAME"))
                                    .setType(rs.getString("TYPE_NAME"))
                                    .setPrecision(rs.getInt("COLUMN_SIZE"))
                                    .setScale(rs.getInt("DECIMAL_DIGITS"))
                                    .setNullable(rs.getInt("NULLABLE") == DatabaseMetaData.columnNullable)
                                    .build());
                        }

                        if (!columns.isEmpty()) {
                            tables.add(TableInfo.newBuilder()
                                    .setName(request.getTableName())
                                    .setType("TABLE")
                                    .addAllColumns(columns)
                                    .build());
                        }
                    }
                }

                MetadataResponse response = MetadataResponse.newBuilder()
                        .addAllTables(tables)
                        .build();

                responseObserver.onNext(response);
                responseObserver.onCompleted();
            }

        } catch (Exception e) {
            MetadataResponse response = MetadataResponse.newBuilder()
                    .setError(e.getMessage())
                    .build();
            responseObserver.onNext(response);
            responseObserver.onCompleted();
        }
    }

    // Helper methods

    private void setParameters(PreparedStatement pstmt, List<Parameter> parameters) throws SQLException {
        for (int i = 0; i < parameters.size(); i++) {
            Parameter param = parameters.get(i);
            int paramIndex = i + 1;

            if (param.getIsNull()) {
                pstmt.setNull(paramIndex, Types.NULL);
                continue;
            }

            switch (param.getValueCase()) {
                case STRING_VALUE:
                    pstmt.setString(paramIndex, param.getStringValue());
                    break;
                case INT_VALUE:
                    pstmt.setInt(paramIndex, param.getIntValue());
                    break;
                case LONG_VALUE:
                    pstmt.setLong(paramIndex, param.getLongValue());
                    break;
                case DOUBLE_VALUE:
                    pstmt.setDouble(paramIndex, param.getDoubleValue());
                    break;
                case BOOL_VALUE:
                    pstmt.setBoolean(paramIndex, param.getBoolValue());
                    break;
                case BYTES_VALUE:
                    pstmt.setBytes(paramIndex, param.getBytesValue().toByteArray());
                    break;
                default:
                    pstmt.setNull(paramIndex, Types.NULL);
            }
        }
    }

    private Value convertValue(ResultSet rs, int columnIndex, int sqlType) throws SQLException {
        Value.Builder valueBuilder = Value.newBuilder();

        Object value = rs.getObject(columnIndex);

        if (value == null) {
            return valueBuilder.setIsNull(true).build();
        }

        // Map SQL types to protobuf types
        switch (sqlType) {
            case Types.VARCHAR:
            case Types.CHAR:
            case Types.LONGVARCHAR:
            case Types.CLOB:
                valueBuilder.setStringData(rs.getString(columnIndex));
                break;
            case Types.INTEGER:
            case Types.SMALLINT:
            case Types.TINYINT:
                valueBuilder.setIntData(rs.getInt(columnIndex));
                break;
            case Types.BIGINT:
                valueBuilder.setLongData(rs.getLong(columnIndex));
                break;
            case Types.DECIMAL:
            case Types.NUMERIC:
            case Types.REAL:
            case Types.FLOAT:
            case Types.DOUBLE:
                valueBuilder.setDoubleData(rs.getDouble(columnIndex));
                break;
            case Types.BOOLEAN:
            case Types.BIT:
                valueBuilder.setBoolData(rs.getBoolean(columnIndex));
                break;
            case Types.BINARY:
            case Types.VARBINARY:
            case Types.LONGVARBINARY:
            case Types.BLOB:
                byte[] bytes = rs.getBytes(columnIndex);
                if (bytes != null) {
                    valueBuilder.setBytesData(com.google.protobuf.ByteString.copyFrom(bytes));
                }
                break;
            case Types.DATE:
            case Types.TIME:
            case Types.TIMESTAMP:
                valueBuilder.setStringData(rs.getString(columnIndex));
                break;
            default:
                // Fallback to string representation
                valueBuilder.setStringData(value.toString());
        }

        return valueBuilder.build();
    }
}