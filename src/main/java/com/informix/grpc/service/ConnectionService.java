package com.informix.grpc.service;

import com.informix.grpc.*;
import com.informix.grpc.metrics.GrpcMetrics;
import com.informix.grpc.pool.PoolManager;
import com.zaxxer.hikari.HikariDataSource;
import io.grpc.stub.StreamObserver;

import java.sql.Connection;
import java.sql.DatabaseMetaData;

public class ConnectionService {

    private final PoolManager poolManager;
    private final GrpcMetrics metrics;

    public ConnectionService(PoolManager poolManager, GrpcMetrics metrics) {
        this.poolManager = poolManager;
        this.metrics = metrics;
    }

    public void connect(ConnectionRequest request, StreamObserver<ConnectionResponse> responseObserver) {
        var timer = metrics.startGrpcTimer("Connect");
        try {
            String connectionId = poolManager.createPool(request);
            metrics.incActiveConnections();

            // Retrieve server version from the new pool
            String version = "Unknown";
            try (Connection conn = poolManager.getPool(connectionId).getConnection()) {
                DatabaseMetaData meta = conn.getMetaData();
                version = meta.getDatabaseProductName() + " " + meta.getDatabaseProductVersion();
            }

            ConnectionResponse response = ConnectionResponse.newBuilder()
                    .setConnectionId(connectionId)
                    .setServerVersion(version)
                    .setSuccess(true)
                    .build();

            metrics.recordGrpcRequest("Connect", "ok");
            responseObserver.onNext(response);
            responseObserver.onCompleted();
        } catch (Exception e) {
            metrics.recordGrpcRequest("Connect", "error");
            String errorMsg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            if (e.getCause() != null && e.getCause().getMessage() != null) {
                errorMsg = e.getCause().getMessage();
            }
            ConnectionResponse response = ConnectionResponse.newBuilder()
                    .setSuccess(false)
                    .setError("Failed to initialize pool: " + errorMsg)
                    .build();
            responseObserver.onNext(response);
            responseObserver.onCompleted();
        } finally {
            timer.observeDuration();
        }
    }

    public void disconnect(DisconnectRequest request, StreamObserver<DisconnectResponse> responseObserver) {
        var timer = metrics.startGrpcTimer("Disconnect");
        try {
            poolManager.removePool(request.getConnectionId());
            metrics.decActiveConnections();
            metrics.recordGrpcRequest("Disconnect", "ok");
            responseObserver.onNext(DisconnectResponse.newBuilder().setSuccess(true).build());
            responseObserver.onCompleted();
        } catch (Exception e) {
            metrics.recordGrpcRequest("Disconnect", "error");
            responseObserver.onNext(DisconnectResponse.newBuilder().setSuccess(false).build());
            responseObserver.onCompleted();
        } finally {
            timer.observeDuration();
        }
    }

    public void ping(PingRequest request, StreamObserver<PingResponse> responseObserver) {
        var timer = metrics.startGrpcTimer("Ping");
        long startTime = System.currentTimeMillis();
        try {
            HikariDataSource ds = poolManager.getPool(request.getConnectionId());
            if (ds == null) throw new RuntimeException("Connection not found");

            try (Connection conn = ds.getConnection();
                 var stmt = conn.createStatement();
                 var rs = stmt.executeQuery("SELECT 1 FROM systables WHERE tabid = 1")) {
                rs.next();
            }

            long latency = System.currentTimeMillis() - startTime;
            metrics.recordGrpcRequest("Ping", "ok");
            responseObserver.onNext(PingResponse.newBuilder().setAlive(true).setLatencyMs(latency).build());
            responseObserver.onCompleted();
        } catch (Exception e) {
            metrics.recordGrpcRequest("Ping", "error");
            responseObserver.onNext(PingResponse.newBuilder().setAlive(false).setLatencyMs(-1).build());
            responseObserver.onCompleted();
        } finally {
            timer.observeDuration();
        }
    }
}
