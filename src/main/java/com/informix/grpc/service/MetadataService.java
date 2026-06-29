package com.informix.grpc.service;

import com.informix.grpc.*;
import com.informix.grpc.metrics.GrpcMetrics;
import com.informix.grpc.pool.PoolManager;
import com.zaxxer.hikari.HikariDataSource;
import io.grpc.stub.StreamObserver;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

public class MetadataService {

    private final PoolManager poolManager;
    private final GrpcMetrics metrics;

    public MetadataService(PoolManager poolManager, GrpcMetrics metrics) {
        this.poolManager = poolManager;
        this.metrics = metrics;
    }

    public void getMetadata(MetadataRequest request, StreamObserver<MetadataResponse> responseObserver) {
        var timer = metrics.startGrpcTimer("GetMetadata");
        try {
            HikariDataSource ds = poolManager.getPool(request.getConnectionId());
            if (ds == null) throw new SQLException("Connection not found");

            try (Connection conn = ds.getConnection()) {
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

                metrics.recordGrpcRequest("GetMetadata", "ok");
                responseObserver.onNext(MetadataResponse.newBuilder().addAllTables(tables).build());
                responseObserver.onCompleted();
            }
        } catch (Exception e) {
            metrics.recordGrpcRequest("GetMetadata", "error");
            responseObserver.onNext(MetadataResponse.newBuilder().setError(e.getMessage()).build());
            responseObserver.onCompleted();
        } finally {
            timer.observeDuration();
        }
    }
}
