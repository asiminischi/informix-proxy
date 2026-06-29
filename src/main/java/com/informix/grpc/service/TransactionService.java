package com.informix.grpc.service;

import com.informix.grpc.*;
import com.informix.grpc.metrics.GrpcMetrics;
import com.informix.grpc.pool.PoolManager;
import io.grpc.stub.StreamObserver;
import com.zaxxer.hikari.HikariDataSource;

import java.sql.Connection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class TransactionService {

    private final PoolManager poolManager;
    private final GrpcMetrics metrics;
    private final Map<String, Connection> activeConnections = new ConcurrentHashMap<>();

    public TransactionService(PoolManager poolManager, GrpcMetrics metrics) {
        this.poolManager = poolManager;
        this.metrics = metrics;
    }

    // Allows QueryService and others to retrieve the transactional connection
    public Connection getActiveConnection(String connectionId) {
        return activeConnections.get(connectionId);
    }

    public void removeActiveConnection(String connectionId) {
        activeConnections.remove(connectionId);
    }

    public void beginTransaction(TransactionRequest request, StreamObserver<TransactionResponse> responseObserver) {
        var timer = metrics.startGrpcTimer("BeginTransaction");
        try {
            metrics.incTransaction("begin");
            HikariDataSource ds = poolManager.getPool(request.getConnectionId());
            if (ds == null) throw new RuntimeException("Connection not found");

            Connection conn = ds.getConnection();
            conn.setAutoCommit(false);

            String isolation = request.getIsolationLevel();
            if (isolation != null && !isolation.isEmpty()) {
                switch (isolation) {
                    case "READ_UNCOMMITTED": conn.setTransactionIsolation(Connection.TRANSACTION_READ_UNCOMMITTED); break;
                    case "READ_COMMITTED":   conn.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED); break;
                    case "REPEATABLE_READ":  conn.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ); break;
                    case "SERIALIZABLE":     conn.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE); break;
                }
            }

            activeConnections.put(request.getConnectionId(), conn);
            metrics.recordGrpcRequest("BeginTransaction", "ok");
            responseObserver.onNext(TransactionResponse.newBuilder().setSuccess(true).build());
            responseObserver.onCompleted();
        } catch (Exception e) {
            metrics.recordGrpcRequest("BeginTransaction", "error");
            responseObserver.onNext(TransactionResponse.newBuilder().setSuccess(false).setError(e.getMessage()).build());
            responseObserver.onCompleted();
        } finally {
            timer.observeDuration();
        }
    }

    public void commit(CommitRequest request, StreamObserver<CommitResponse> responseObserver) {
        var timer = metrics.startGrpcTimer("Commit");
        try {
            metrics.incTransaction("commit");
            Connection conn = activeConnections.remove(request.getConnectionId());
            if (conn == null) throw new RuntimeException("No active transaction");

            conn.commit();
            conn.setAutoCommit(true);
            conn.close();

            metrics.recordGrpcRequest("Commit", "ok");
            responseObserver.onNext(CommitResponse.newBuilder().setSuccess(true).build());
            responseObserver.onCompleted();
        } catch (Exception e) {
            metrics.recordGrpcRequest("Commit", "error");
            responseObserver.onNext(CommitResponse.newBuilder().setSuccess(false).setError(e.getMessage()).build());
            responseObserver.onCompleted();
        } finally {
            timer.observeDuration();
        }
    }

    public void rollback(RollbackRequest request, StreamObserver<RollbackResponse> responseObserver) {
        var timer = metrics.startGrpcTimer("Rollback");
        try {
            metrics.incTransaction("rollback");
            Connection conn = activeConnections.remove(request.getConnectionId());
            if (conn == null) throw new RuntimeException("No active transaction");

            conn.rollback();
            conn.setAutoCommit(true);
            conn.close();

            metrics.recordGrpcRequest("Rollback", "ok");
            responseObserver.onNext(RollbackResponse.newBuilder().setSuccess(true).build());
            responseObserver.onCompleted();
        } catch (Exception e) {
            metrics.recordGrpcRequest("Rollback", "error");
            responseObserver.onNext(RollbackResponse.newBuilder().setSuccess(false).setError(e.getMessage()).build());
            responseObserver.onCompleted();
        } finally {
            timer.observeDuration();
        }
    }
}
