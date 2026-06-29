package com.informix.grpc.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.informix.grpc.*;
import com.informix.grpc.metrics.GrpcMetrics;
import com.informix.grpc.pool.PoolManager;
import com.zaxxer.hikari.HikariDataSource;
import io.grpc.stub.StreamObserver;
import io.prometheus.client.Histogram;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class MetadataServiceTest {

    @Mock PoolManager poolManager;
    @Mock GrpcMetrics metrics;
    @Mock StreamObserver<MetadataResponse> observer;
    @Captor ArgumentCaptor<MetadataResponse> captor;
    @Mock Histogram.Timer timer;

    private MetadataService service;

    @BeforeEach
    void setUp() {
        service = new MetadataService(poolManager, metrics);
        when(metrics.startGrpcTimer(any())).thenReturn(timer);
    }

    @Test
    void shouldReturnAllTablesWhenNoTableNameSpecified() throws Exception {
        MetadataRequest req = MetadataRequest.newBuilder().setConnectionId("c1").build();
        HikariDataSource ds = mock(HikariDataSource.class);
        Connection conn = mock(Connection.class);
        DatabaseMetaData dbMeta = mock(DatabaseMetaData.class);
        ResultSet tablesRs = mock(ResultSet.class);

        when(poolManager.getPool("c1")).thenReturn(ds);
        when(ds.getConnection()).thenReturn(conn);
        when(conn.getMetaData()).thenReturn(dbMeta);
        when(dbMeta.getTables(null, null, "%", new String[]{"TABLE"})).thenReturn(tablesRs);
        when(tablesRs.next()).thenReturn(true, true, false);
        when(tablesRs.getString("TABLE_NAME")).thenReturn("customer", "order");
        when(tablesRs.getString("TABLE_SCHEM")).thenReturn("public", "public");

        service.getMetadata(req, observer);

        verify(metrics).recordGrpcRequest("GetMetadata", "ok");
        verify(observer).onNext(captor.capture());
        MetadataResponse resp = captor.getValue();
        assertThat(resp.getTablesList()).hasSize(2);
        assertThat(resp.getTables(0).getName()).isEqualTo("customer");
        assertThat(resp.getTables(1).getName()).isEqualTo("order");
    }

    @Test
    void shouldReturnColumnsForSpecificTable() throws Exception {
        MetadataRequest req = MetadataRequest.newBuilder()
                .setConnectionId("c1").setTableName("customer").build();
        HikariDataSource ds = mock(HikariDataSource.class);
        Connection conn = mock(Connection.class);
        DatabaseMetaData dbMeta = mock(DatabaseMetaData.class);
        ResultSet columnsRs = mock(ResultSet.class);

        when(poolManager.getPool("c1")).thenReturn(ds);
        when(ds.getConnection()).thenReturn(conn);
        when(conn.getMetaData()).thenReturn(dbMeta);
        when(dbMeta.getColumns(null, null, "customer", "%")).thenReturn(columnsRs);
        when(columnsRs.next()).thenReturn(true, false);
        when(columnsRs.getString("COLUMN_NAME")).thenReturn("id");
        when(columnsRs.getString("TYPE_NAME")).thenReturn("INTEGER");
        when(columnsRs.getInt("COLUMN_SIZE")).thenReturn(9);
        when(columnsRs.getInt("DECIMAL_DIGITS")).thenReturn(0);
        when(columnsRs.getInt("NULLABLE")).thenReturn(DatabaseMetaData.columnNoNulls);

        service.getMetadata(req, observer);

        verify(observer).onNext(captor.capture());
        MetadataResponse resp = captor.getValue();
        assertThat(resp.getTablesList()).hasSize(1);
        TableInfo table = resp.getTables(0);
        assertThat(table.getName()).isEqualTo("customer");
        assertThat(table.getColumnsCount()).isEqualTo(1);
        assertThat(table.getColumns(0).getName()).isEqualTo("id");
        assertThat(table.getColumns(0).getNullable()).isFalse();
    }

    @Test
    void shouldReturnErrorWhenPoolMissing() {
        MetadataRequest req = MetadataRequest.newBuilder().setConnectionId("bad").build();
        when(poolManager.getPool("bad")).thenReturn(null);

        service.getMetadata(req, observer);

        verify(metrics).recordGrpcRequest("GetMetadata", "error");
        verify(observer).onNext(argThat(r -> r.getError().contains("not found")));
    }
}
