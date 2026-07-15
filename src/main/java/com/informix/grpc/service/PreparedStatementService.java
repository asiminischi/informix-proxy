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
import java.util.ArrayList;
import java.util.List;

public class PreparedStatementService {

    private final PoolManager poolManager;
    private final PreparedStatementCache stmtCache;
    private final GrpcMetrics metrics;

    public PreparedStatementService(PoolManager poolManager, PreparedStatementCache stmtCache, GrpcMetrics metrics) {
        this.poolManager = poolManager;
        this.stmtCache = stmtCache;
        this.metrics = metrics;
    }

    public void prepareStatement(PrepareRequest request, StreamObserver<PrepareResponse> responseObserver) {
        var timer = metrics.startGrpcTimer("PrepareStatement");
        try {
            HikariDataSource ds = poolManager.getPool(request.getConnectionId());
            if (ds == null) throw new SQLException("Connection not found");

            Connection conn = ds.getConnection();
            PreparedStatement pstmt;
            int parameterCount;
            try {
                pstmt = conn.prepareStatement(request.getSql());
                parameterCount = pstmt.getParameterMetaData().getParameterCount();
            } catch (Exception e) {
                // Nothing has been cached yet, so this connection would
                // otherwise never be returned to the pool.
                try { conn.close(); } catch (Exception ignored) {}
                throw e;
            }

            // Only cache once every fallible step above has succeeded - if we
            // cached earlier and a later step failed, the statement id would
            // never reach the client and the cached statement+connection
            // would leak forever.
            String statementId = stmtCache.put(request.getConnectionId(), pstmt);

            PrepareResponse response = PrepareResponse.newBuilder()
                    .setStatementId(statementId)
                    .setParameterCount(parameterCount)
                    .build();

            metrics.recordGrpcRequest("PrepareStatement", "ok");
            responseObserver.onNext(response);
            responseObserver.onCompleted();
        } catch (Exception e) {
            metrics.recordGrpcRequest("PrepareStatement", "error");
            metrics.incQueryError();
            responseObserver.onNext(PrepareResponse.newBuilder().setError(e.getMessage()).build());
            responseObserver.onCompleted();
        } finally {
            timer.observeDuration();
        }
    }

    public void executePrepared(ExecutePreparedRequest request, StreamObserver<QueryResponse> responseObserver) {
        var timer = metrics.startGrpcTimer("ExecutePrepared");
        try {
            metrics.incQuery("prepared");
            PreparedStatement pstmt = stmtCache.get(request.getStatementId());
            if (pstmt == null) throw new SQLException("Prepared statement not found");

            ParameterBinder.bind(pstmt, request.getParametersList());

            int fetchSize = request.getFetchSize() > 0 ? request.getFetchSize() : 100;
            pstmt.setFetchSize(fetchSize);

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

                metrics.recordGrpcRequest("ExecutePrepared", "ok");
                responseObserver.onNext(finalResp.build());
                responseObserver.onCompleted();
            }
        } catch (Exception e) {
            metrics.recordGrpcRequest("ExecutePrepared", "error");
            metrics.incQueryError();
            responseObserver.onNext(QueryResponse.newBuilder().setError(e.getMessage()).build());
            responseObserver.onCompleted();
        } finally {
            timer.observeDuration();
        }
    }

    public void closePrepared(ClosePreparedRequest request, StreamObserver<ClosePreparedResponse> responseObserver) {
        var timer = metrics.startGrpcTimer("ClosePrepared");
        try {
            stmtCache.removeAndClose(request.getStatementId());
            metrics.recordGrpcRequest("ClosePrepared", "ok");
            responseObserver.onNext(ClosePreparedResponse.newBuilder().setSuccess(true).build());
            responseObserver.onCompleted();
        } catch (Exception e) {
            metrics.recordGrpcRequest("ClosePrepared", "error");
            responseObserver.onNext(ClosePreparedResponse.newBuilder().setSuccess(false).build());
            responseObserver.onCompleted();
        } finally {
            timer.observeDuration();
        }
    }
}
