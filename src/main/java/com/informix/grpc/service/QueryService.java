package com.informix.grpc.service;

import com.informix.grpc.*;
import com.informix.grpc.cache.PreparedStatementCache;
import com.informix.grpc.metrics.GrpcMetrics;
import com.informix.grpc.pool.PoolManager;
import com.informix.grpc.util.ParameterBinder;
import com.informix.grpc.util.ResultSetConverter;
import io.grpc.stub.StreamObserver;
import com.zaxxer.hikari.HikariDataSource;

import java.sql.*;
import java.util.*;

public class QueryService {

    private final PoolManager poolManager;
    private final PreparedStatementCache stmtCache;
    private final TransactionService transactionService;
    private final GrpcMetrics metrics;

    public QueryService(PoolManager poolManager,
                        PreparedStatementCache stmtCache,
                        TransactionService transactionService,
                        GrpcMetrics metrics) {
        this.poolManager = poolManager;
        this.stmtCache = stmtCache;
        this.transactionService = transactionService;
        this.metrics = metrics;
    }

    public void executeQuery(QueryRequest request, StreamObserver<QueryResponse> responseObserver) {
        var timer = metrics.startGrpcTimer("ExecuteQuery");
        try {
            metrics.incQuery("query");
            Connection conn = acquireTransactionalConnection(request.getConnectionId());
            boolean closeConnection = false;

            if (conn == null) {
                HikariDataSource ds = poolManager.getPool(request.getConnectionId());
                
                if (ds == null) {
                    throw new SQLException("Connection not found");
                }
                conn = ds.getConnection();

                closeConnection = true;
            }

            try (PreparedStatement pstmt = conn.prepareStatement(request.getSql())) {
                ParameterBinder.bind(pstmt, request.getParametersList());

                int fetchSize = request.getFetchSize() > 0 ? request.getFetchSize() : 100;
                pstmt.setFetchSize(fetchSize);
                if (request.getMaxRows() > 0) pstmt.setMaxRows(request.getMaxRows());

                try (ResultSet rs = pstmt.executeQuery()) {
                    ResultSetMetaData meta = rs.getMetaData();
                    int columnCount = meta.getColumnCount();

                    List<ColumnMetadata> columns = ResultSetConverter.extractColumnMetadata(meta);

                    List<Row> rowBatch = new ArrayList<>();
                    int totalRows = 0;

                    while (rs.next()) {
                        Row.Builder row = Row.newBuilder();
                        for (int i = 1; i <= columnCount; i++) {
                            row.addValues(ResultSetConverter.convertValue(rs, i, meta.getColumnType(i)));
                        }
                        rowBatch.add(row.build());
                        totalRows++;

                        if (rowBatch.size() >= fetchSize) {
                            QueryResponse.Builder resp = QueryResponse.newBuilder()
                                    .addAllRows(rowBatch)
                                    .setHasMore(true)
                                    .setTotalRows(totalRows);
                            if (totalRows == rowBatch.size()) resp.addAllColumns(columns);
                            responseObserver.onNext(resp.build());
                            rowBatch.clear();
                        }
                    }

                    QueryResponse.Builder finalResp = QueryResponse.newBuilder()
                            .addAllRows(rowBatch)
                            .setHasMore(false)
                            .setTotalRows(totalRows);
                    if (totalRows == rowBatch.size()) finalResp.addAllColumns(columns);
                    metrics.recordGrpcRequest("ExecuteQuery", "ok");
                    responseObserver.onNext(finalResp.build());
                    responseObserver.onCompleted();
                }
            } finally {
                if (closeConnection && conn != null && !conn.isClosed()) conn.close();
            }
        } catch (Exception e) {
            metrics.recordGrpcRequest("ExecuteQuery", "error");
            metrics.incQueryError();
            responseObserver.onNext(QueryResponse.newBuilder().setError(e.getMessage()).build());
            responseObserver.onCompleted();
        } finally {
            timer.observeDuration();
        }
    }

    public void executeUpdate(UpdateRequest request, StreamObserver<UpdateResponse> responseObserver) {
        var timer = metrics.startGrpcTimer("ExecuteUpdate");
        try {
            metrics.incQuery("update");
            Connection conn = acquireTransactionalConnection(request.getConnectionId());
            boolean closeConnection = false;

            if (conn == null) {
                HikariDataSource ds = poolManager.getPool(request.getConnectionId());
                
                if (ds == null) {
                    throw new SQLException("Connection not found");
                }
                conn = ds.getConnection();

                closeConnection = true;
            }

            try (PreparedStatement pstmt = conn.prepareStatement(request.getSql())) {
                ParameterBinder.bind(pstmt, request.getParametersList());
                int rowsAffected = pstmt.executeUpdate();
                metrics.recordGrpcRequest("ExecuteUpdate", "ok");
                responseObserver.onNext(UpdateResponse.newBuilder().setRowsAffected(rowsAffected).build());
                responseObserver.onCompleted();
            } finally {
                if (closeConnection && conn != null && !conn.isClosed()) conn.close();
            }
        } catch (Exception e) {
            metrics.recordGrpcRequest("ExecuteUpdate", "error");
            metrics.incQueryError();
            responseObserver.onNext(UpdateResponse.newBuilder().setRowsAffected(-1).setError(e.getMessage()).build());
            responseObserver.onCompleted();
        } finally {
            timer.observeDuration();
        }
    }

    public void executeBatch(BatchRequest request, StreamObserver<BatchResponse> responseObserver) {
        var timer = metrics.startGrpcTimer("ExecuteBatch");
        try {
            metrics.incQuery("batch");
            Connection conn = acquireTransactionalConnection(request.getConnectionId());
            boolean closeConnection = false;

            if (conn == null) {
                HikariDataSource ds = poolManager.getPool(request.getConnectionId());
                if (ds == null) {
                    throw new SQLException("Connection not found");
                }
                conn = ds.getConnection(); 

                closeConnection = true;
            }

            try (Statement stmt = conn.createStatement()) {
                for (String sql : request.getSqlStatementsList()) {
                    stmt.addBatch(sql);
                }
                int[] results = stmt.executeBatch();
                BatchResponse.Builder resp = BatchResponse.newBuilder();
                for (int r : results) resp.addRowsAffected(r);
                metrics.recordGrpcRequest("ExecuteBatch", "ok");
                responseObserver.onNext(resp.build());
                responseObserver.onCompleted();
            } finally {
                if (closeConnection && conn != null && !conn.isClosed()) conn.close();
            }
        } catch (Exception e) {
            metrics.recordGrpcRequest("ExecuteBatch", "error");
            metrics.incQueryError();
            responseObserver.onNext(BatchResponse.newBuilder().setError(e.getMessage()).build());
            responseObserver.onCompleted();
        } finally {
            timer.observeDuration();
        }
    }

    /**
     * Returns the active transactional connection if present, otherwise null.
     */
    private Connection acquireTransactionalConnection(String connectionId) {
        return transactionService.getActiveConnection(connectionId);
    }
}
