package com.informix.grpc;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.stub.StreamObserver;
import io.prometheus.client.*;
import io.prometheus.client.exporter.HTTPServer;
import io.prometheus.client.hotspot.DefaultExports;

import java.sql.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

public class InformixProxyServer extends InformixServiceGrpc.InformixServiceImplBase {

    private final Map<String, HikariDataSource> connectionPools = new ConcurrentHashMap<>();
    private final Map<String, PreparedStatement> preparedStatements = new ConcurrentHashMap<>();
    private final AtomicLong connectionIdCounter = new AtomicLong(0);
    private final AtomicLong statementIdCounter = new AtomicLong(0);
    private final Map<String, Connection> activeConnections = new ConcurrentHashMap<>();

    // -- Prometheus metrics --

    private static final Gauge clientConnections = Gauge.build()
            .name("informix_connections_active")
            .help("Number of active client connection pools")
            .register();

    private static final Counter queryCounter = Counter.build()
            .name("informix_queries_total")
            .help("Total queries executed")
            .labelNames("type")
            .register();

    private static final Counter queryErrorCounter = Counter.build()
            .name("informix_query_errors_total")
            .help("Total query errors")
            .register();

    private static final Counter transactionCounter = Counter.build()
            .name("informix_transactions_total")
            .help("Total transactions")
            .labelNames("type")
            .register();

    private static final Histogram grpcLatency = Histogram.build()
            .name("grpc_server_handling_seconds")
            .help("gRPC request handling duration in seconds")
            .labelNames("method")
            .buckets(0.001, 0.005, 0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1.0, 2.5, 5.0, 10.0)
            .register();

    private static final Counter grpcRequests = Counter.build()
            .name("grpc_server_handled_total")
            .help("Total gRPC requests by method and status")
            .labelNames("method", "status")
            .register();

    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(System.getenv().getOrDefault("GRPC_PORT", "50051"));
        int metricsPort = Integer.parseInt(System.getenv().getOrDefault("METRICS_PORT", "9090"));

        DefaultExports.initialize();
        InformixProxyServer service = new InformixProxyServer();
        new PoolStatsCollector(service.connectionPools).register();
        HTTPServer metricsServer = new HTTPServer.Builder().withPort(metricsPort).build();
        System.out.println("Metrics server started on port " + metricsPort);

        Server server = ServerBuilder.forPort(port)
                .addService(service)
                .maxInboundMessageSize(50 * 1024 * 1024)
                .build()
                .start();

        System.out.println("Informix gRPC Proxy started on port " + port);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("Shutting down gRPC server...");
            metricsServer.close();
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
        Histogram.Timer timer = grpcLatency.labels("Connect").startTimer();
        try {
            String connectionId = "conn_" + connectionIdCounter.incrementAndGet();

            String jdbcUrl = String.format(
                    "jdbc:informix-sqli://%s:%d/%s",
                    request.getHost(),
                    request.getPort(),
                    request.getDatabase()
            );

            if (!request.getPropertiesMap().isEmpty()) {
                StringBuilder urlBuilder = new StringBuilder(jdbcUrl);
                for (Map.Entry<String, String> entry : request.getPropertiesMap().entrySet()) {
                    urlBuilder.append(";").append(entry.getKey()).append("=").append(entry.getValue());
                }
                jdbcUrl = urlBuilder.toString();
            }

            HikariConfig config = new HikariConfig();
            config.setJdbcUrl(jdbcUrl);
            config.setUsername(request.getUsername());
            config.setPassword(request.getPassword());
            config.setDriverClassName("com.informix.jdbc.IfxDriver");

            int poolSize = request.getPoolSize() > 0 ? request.getPoolSize() : 10;
            config.setMaximumPoolSize(poolSize);
            config.setMinimumIdle(2);
            config.setConnectionTimeout(30000);
            config.setIdleTimeout(600000);
            config.setMaxLifetime(1800000);
            config.setConnectionTestQuery("SELECT 1 FROM systables WHERE tabid = 1");

            HikariDataSource dataSource = new HikariDataSource(config);

            String serverVersion = null;
            try (Connection conn = dataSource.getConnection()) {
                DatabaseMetaData meta = conn.getMetaData();
                serverVersion = meta.getDatabaseProductName() + " " + meta.getDatabaseProductVersion();
            }

            connectionPools.put(connectionId, dataSource);
            clientConnections.inc();

            ConnectionResponse response = ConnectionResponse.newBuilder()
                    .setConnectionId(connectionId)
                    .setServerVersion(serverVersion)
                    .setSuccess(true)
                    .build();

            grpcRequests.labels("Connect", "ok").inc();
            responseObserver.onNext(response);
            responseObserver.onCompleted();

        } catch (Exception e) {
            grpcRequests.labels("Connect", "error").inc();
            ConnectionResponse response = ConnectionResponse.newBuilder()
                    .setSuccess(false)
                    .setError(e.getMessage())
                    .build();
            responseObserver.onNext(response);
            responseObserver.onCompleted();
        } finally {
            timer.observeDuration();
        }
    }

    @Override
    public void disconnect(DisconnectRequest request, StreamObserver<DisconnectResponse> responseObserver) {
        Histogram.Timer timer = grpcLatency.labels("Disconnect").startTimer();
        try {
            HikariDataSource dataSource = connectionPools.remove(request.getConnectionId());

            if (dataSource != null) {
                dataSource.close();
                clientConnections.dec();
            }

            Connection conn = activeConnections.remove(request.getConnectionId());
            if (conn != null && !conn.isClosed()) {
                conn.close();
            }

            DisconnectResponse response = DisconnectResponse.newBuilder()
                    .setSuccess(true)
                    .build();

            grpcRequests.labels("Disconnect", "ok").inc();
            responseObserver.onNext(response);
            responseObserver.onCompleted();

        } catch (Exception e) {
            grpcRequests.labels("Disconnect", "error").inc();
            DisconnectResponse response = DisconnectResponse.newBuilder()
                    .setSuccess(false)
                    .build();
            responseObserver.onNext(response);
            responseObserver.onCompleted();
        } finally {
            timer.observeDuration();
        }
    }

    @Override
    public void ping(PingRequest request, StreamObserver<PingResponse> responseObserver) {
        Histogram.Timer timer = grpcLatency.labels("Ping").startTimer();
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

            grpcRequests.labels("Ping", "ok").inc();
            responseObserver.onNext(response);
            responseObserver.onCompleted();

        } catch (Exception e) {
            grpcRequests.labels("Ping", "error").inc();
            PingResponse response = PingResponse.newBuilder()
                    .setAlive(false)
                    .setLatencyMs(-1)
                    .build();
            responseObserver.onNext(response);
            responseObserver.onCompleted();
        } finally {
            timer.observeDuration();
        }
    }

    @Override
    public void executeQuery(QueryRequest request, StreamObserver<QueryResponse> responseObserver) {
        Histogram.Timer timer = grpcLatency.labels("ExecuteQuery").startTimer();
        try {
            queryCounter.labels("query").inc();
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

                int fetchSize = request.getFetchSize() > 0 ? request.getFetchSize() : 100;
                pstmt.setFetchSize(fetchSize);

                if (request.getMaxRows() > 0) {
                    pstmt.setMaxRows(request.getMaxRows());
                }

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

                    grpcRequests.labels("ExecuteQuery", "ok").inc();
                    responseObserver.onNext(finalResponse.build());
                    responseObserver.onCompleted();
                }
            } finally {
                if (closeConnection && conn != null) {
                    conn.close();
                }
            }

        } catch (Exception e) {
            grpcRequests.labels("ExecuteQuery", "error").inc();
            queryErrorCounter.inc();
            QueryResponse error = QueryResponse.newBuilder()
                    .setError(e.getMessage())
                    .build();
            responseObserver.onNext(error);
            responseObserver.onCompleted();
        } finally {
            timer.observeDuration();
        }
    }

    @Override
    public void executeUpdate(UpdateRequest request, StreamObserver<UpdateResponse> responseObserver) {
        Histogram.Timer timer = grpcLatency.labels("ExecuteUpdate").startTimer();
        try {
            queryCounter.labels("update").inc();
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

                grpcRequests.labels("ExecuteUpdate", "ok").inc();
                responseObserver.onNext(response);
                responseObserver.onCompleted();
            } finally {
                if (closeConnection && conn != null) {
                    conn.close();
                }
            }

        } catch (Exception e) {
            grpcRequests.labels("ExecuteUpdate", "error").inc();
            queryErrorCounter.inc();
            UpdateResponse response = UpdateResponse.newBuilder()
                    .setRowsAffected(-1)
                    .setError(e.getMessage())
                    .build();
            responseObserver.onNext(response);
            responseObserver.onCompleted();
        } finally {
            timer.observeDuration();
        }
    }

    @Override
    public void executeBatch(BatchRequest request, StreamObserver<BatchResponse> responseObserver) {
        Histogram.Timer timer = grpcLatency.labels("ExecuteBatch").startTimer();
        try {
            queryCounter.labels("batch").inc();
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

                grpcRequests.labels("ExecuteBatch", "ok").inc();
                responseObserver.onNext(responseBuilder.build());
                responseObserver.onCompleted();
            } finally {
                if (closeConnection && conn != null) {
                    conn.close();
                }
            }

        } catch (Exception e) {
            grpcRequests.labels("ExecuteBatch", "error").inc();
            queryErrorCounter.inc();
            BatchResponse response = BatchResponse.newBuilder()
                    .setError(e.getMessage())
                    .build();
            responseObserver.onNext(response);
            responseObserver.onCompleted();
        } finally {
            timer.observeDuration();
        }
    }

    @Override
    public void prepareStatement(PrepareRequest request, StreamObserver<PrepareResponse> responseObserver) {
        Histogram.Timer timer = grpcLatency.labels("PrepareStatement").startTimer();
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

            grpcRequests.labels("PrepareStatement", "ok").inc();
            responseObserver.onNext(response);
            responseObserver.onCompleted();

        } catch (Exception e) {
            grpcRequests.labels("PrepareStatement", "error").inc();
            queryErrorCounter.inc();
            PrepareResponse response = PrepareResponse.newBuilder()
                    .setError(e.getMessage())
                    .build();
            responseObserver.onNext(response);
            responseObserver.onCompleted();
        } finally {
            timer.observeDuration();
        }
    }

    @Override
    public void executePrepared(ExecutePreparedRequest request, StreamObserver<QueryResponse> responseObserver) {
        Histogram.Timer timer = grpcLatency.labels("ExecutePrepared").startTimer();
        try {
            queryCounter.labels("prepared").inc();
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

                grpcRequests.labels("ExecutePrepared", "ok").inc();
                responseObserver.onNext(finalResponse.build());
                responseObserver.onCompleted();
            }

        } catch (Exception e) {
            grpcRequests.labels("ExecutePrepared", "error").inc();
            queryErrorCounter.inc();
            QueryResponse error = QueryResponse.newBuilder()
                    .setError(e.getMessage())
                    .build();
            responseObserver.onNext(error);
            responseObserver.onCompleted();
        } finally {
            timer.observeDuration();
        }
    }

    @Override
    public void closePrepared(ClosePreparedRequest request, StreamObserver<ClosePreparedResponse> responseObserver) {
        Histogram.Timer timer = grpcLatency.labels("ClosePrepared").startTimer();
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

            grpcRequests.labels("ClosePrepared", "ok").inc();
            responseObserver.onNext(response);
            responseObserver.onCompleted();

        } catch (Exception e) {
            grpcRequests.labels("ClosePrepared", "error").inc();
            ClosePreparedResponse response = ClosePreparedResponse.newBuilder()
                    .setSuccess(false)
                    .build();
            responseObserver.onNext(response);
            responseObserver.onCompleted();
        } finally {
            timer.observeDuration();
        }
    }

    @Override
    public void beginTransaction(TransactionRequest request, StreamObserver<TransactionResponse> responseObserver) {
        Histogram.Timer timer = grpcLatency.labels("BeginTransaction").startTimer();
        try {
            transactionCounter.labels("begin").inc();
            HikariDataSource dataSource = connectionPools.get(request.getConnectionId());
            if (dataSource == null) {
                throw new SQLException("Connection not found");
            }

            Connection conn = dataSource.getConnection();
            conn.setAutoCommit(false);

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

            grpcRequests.labels("BeginTransaction", "ok").inc();
            responseObserver.onNext(response);
            responseObserver.onCompleted();

        } catch (Exception e) {
            grpcRequests.labels("BeginTransaction", "error").inc();
            TransactionResponse response = TransactionResponse.newBuilder()
                    .setSuccess(false)
                    .setError(e.getMessage())
                    .build();
            responseObserver.onNext(response);
            responseObserver.onCompleted();
        } finally {
            timer.observeDuration();
        }
    }

    @Override
    public void commit(CommitRequest request, StreamObserver<CommitResponse> responseObserver) {
        Histogram.Timer timer = grpcLatency.labels("Commit").startTimer();
        try {
            transactionCounter.labels("commit").inc();
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

            grpcRequests.labels("Commit", "ok").inc();
            responseObserver.onNext(response);
            responseObserver.onCompleted();

        } catch (Exception e) {
            grpcRequests.labels("Commit", "error").inc();
            CommitResponse response = CommitResponse.newBuilder()
                    .setSuccess(false)
                    .setError(e.getMessage())
                    .build();
            responseObserver.onNext(response);
            responseObserver.onCompleted();
        } finally {
            timer.observeDuration();
        }
    }

    @Override
    public void rollback(RollbackRequest request, StreamObserver<RollbackResponse> responseObserver) {
        Histogram.Timer timer = grpcLatency.labels("Rollback").startTimer();
        try {
            transactionCounter.labels("rollback").inc();
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

            grpcRequests.labels("Rollback", "ok").inc();
            responseObserver.onNext(response);
            responseObserver.onCompleted();

        } catch (Exception e) {
            grpcRequests.labels("Rollback", "error").inc();
            RollbackResponse response = RollbackResponse.newBuilder()
                    .setSuccess(false)
                    .setError(e.getMessage())
                    .build();
            responseObserver.onNext(response);
            responseObserver.onCompleted();
        } finally {
            timer.observeDuration();
        }
    }

    @Override
    public void getMetadata(MetadataRequest request, StreamObserver<MetadataResponse> responseObserver) {
        Histogram.Timer timer = grpcLatency.labels("GetMetadata").startTimer();
        try {
            HikariDataSource dataSource = connectionPools.get(request.getConnectionId());
            if (dataSource == null) {
                throw new SQLException("Connection not found");
            }

            try (Connection conn = dataSource.getConnection()) {
                DatabaseMetaData meta = conn.getMetaData();

                List<TableInfo> tables = new ArrayList<>();

                if (request.getTableName().isEmpty()) {
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

                grpcRequests.labels("GetMetadata", "ok").inc();
                responseObserver.onNext(response);
                responseObserver.onCompleted();
            }

        } catch (Exception e) {
            grpcRequests.labels("GetMetadata", "error").inc();
            MetadataResponse response = MetadataResponse.newBuilder()
                    .setError(e.getMessage())
                    .build();
            responseObserver.onNext(response);
            responseObserver.onCompleted();
        } finally {
            timer.observeDuration();
        }
    }

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
                valueBuilder.setStringData(value.toString());
        }

        return valueBuilder.build();
    }

    /**
     * Custom Prometheus collector that reads HikariCP pool statistics at scrape time.
     */
    private static class PoolStatsCollector extends Collector {
        private final Map<String, HikariDataSource> pools;

        PoolStatsCollector(Map<String, HikariDataSource> pools) {
            this.pools = pools;
        }

        @Override
        public List<MetricFamilySamples> collect() {
            List<MetricFamilySamples> mfs = new ArrayList<>();
            int active = 0, idle = 0, total = 0, pending = 0;

            for (HikariDataSource ds : pools.values()) {
                try {
                    HikariPoolMXBean bean = ds.getHikariPoolMXBean();
                    if (bean != null) {
                        active += bean.getActiveConnections();
                        idle += bean.getIdleConnections();
                        total += bean.getTotalConnections();
                        pending += bean.getThreadsAwaitingConnection();
                    }
                } catch (Exception ignored) {
                }
            }

            mfs.add(new GaugeMetricFamily(
                    "informix_pool_active_connections",
                    "Active JDBC connections across all pools", active));
            mfs.add(new GaugeMetricFamily(
                    "informix_pool_idle_connections",
                    "Idle JDBC connections across all pools", idle));
            mfs.add(new GaugeMetricFamily(
                    "informix_pool_total_connections",
                    "Total JDBC connections across all pools", total));
            mfs.add(new GaugeMetricFamily(
                    "informix_pool_pending_threads",
                    "Threads waiting for a JDBC connection", pending));

            return mfs;
        }
    }
}
