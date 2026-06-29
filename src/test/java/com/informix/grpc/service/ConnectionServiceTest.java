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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ConnectionServiceTest {

    @Mock PoolManager poolManager;
    @Mock GrpcMetrics metrics;
    @Mock StreamObserver<ConnectionResponse> connectionObserver;
    @Mock StreamObserver<DisconnectResponse> disconnectObserver;
    @Mock StreamObserver<PingResponse> pingObserver;
    @Captor ArgumentCaptor<ConnectionResponse> connectionCaptor;
    @Captor ArgumentCaptor<DisconnectResponse> disconnectCaptor;
    @Captor ArgumentCaptor<PingResponse> pingCaptor;
    @Mock Histogram.Timer timer;

    private ConnectionService service;

    @BeforeEach
    void setUp() {
        service = new ConnectionService(poolManager, metrics);
        when(metrics.startGrpcTimer(any())).thenReturn(timer);
    }

    @Test
    void connectShouldReturnSuccess() throws Exception {
        ConnectionRequest req = validRequest();
        HikariDataSource mockDs = mock(HikariDataSource.class);
        Connection mockConn = mock(Connection.class);
        DatabaseMetaData mockMeta = mock(DatabaseMetaData.class);

        when(poolManager.createPool(req)).thenReturn("conn_1");
        when(poolManager.getPool("conn_1")).thenReturn(mockDs);
        when(mockDs.getConnection()).thenReturn(mockConn);
        when(mockConn.getMetaData()).thenReturn(mockMeta);
        when(mockMeta.getDatabaseProductName()).thenReturn("Informix");
        when(mockMeta.getDatabaseProductVersion()).thenReturn("14.10");

        service.connect(req, connectionObserver);

        verify(metrics).incActiveConnections();
        verify(metrics).recordGrpcRequest("Connect", "ok");
        verify(connectionObserver).onNext(connectionCaptor.capture());
        verify(connectionObserver).onCompleted();

        ConnectionResponse resp = connectionCaptor.getValue();
        assertThat(resp.getSuccess()).isTrue();
        assertThat(resp.getConnectionId()).isEqualTo("conn_1");
        assertThat(resp.getServerVersion()).contains("Informix");
    }

    @Test
    void connectShouldReturnErrorWhenPoolCreationFails() throws Exception {
        ConnectionRequest req = validRequest();
        when(poolManager.createPool(req)).thenThrow(new RuntimeException("Connection refused"));

        service.connect(req, connectionObserver);

        verify(metrics).recordGrpcRequest("Connect", "error");
        verify(connectionObserver).onNext(connectionCaptor.capture());
        verify(connectionObserver).onCompleted();

        ConnectionResponse resp = connectionCaptor.getValue();
        assertThat(resp.getSuccess()).isFalse();
        assertThat(resp.getError()).contains("Connection refused");
    }

    @Test
    void disconnectShouldRemovePoolAndDecrementMetric() {
        DisconnectRequest req = DisconnectRequest.newBuilder().setConnectionId("conn_1").build();

        service.disconnect(req, disconnectObserver);

        verify(poolManager).removePool("conn_1");
        verify(metrics).decActiveConnections();
        verify(metrics).recordGrpcRequest("Disconnect", "ok");
        verify(disconnectObserver).onNext(disconnectCaptor.capture());
        assertThat(disconnectCaptor.getValue().getSuccess()).isTrue();
        verify(disconnectObserver).onCompleted();
    }

    @Test
    void disconnectShouldStillSucceedIfPoolDoesNotExist() {
        DisconnectRequest req = DisconnectRequest.newBuilder().setConnectionId("missing").build();

        service.disconnect(req, disconnectObserver);

        verify(metrics).recordGrpcRequest("Disconnect", "ok");
        verify(disconnectObserver).onNext(disconnectCaptor.capture());
        assertThat(disconnectCaptor.getValue().getSuccess()).isTrue();
    }

    @Test
    void pingShouldReturnAliveTrueWhenOnline() throws Exception {
        PingRequest req = PingRequest.newBuilder().setConnectionId("conn_1").build();
        HikariDataSource mockDs = mock(HikariDataSource.class);
        Connection mockConn = mock(Connection.class);
        java.sql.Statement mockStmt = mock(java.sql.Statement.class);
        java.sql.ResultSet mockRs = mock(java.sql.ResultSet.class);

        when(poolManager.getPool("conn_1")).thenReturn(mockDs);
        when(mockDs.getConnection()).thenReturn(mockConn);
        when(mockConn.createStatement()).thenReturn(mockStmt);
        when(mockStmt.executeQuery(anyString())).thenReturn(mockRs);
        when(mockRs.next()).thenReturn(true);

        service.ping(req, pingObserver);

        verify(metrics).recordGrpcRequest("Ping", "ok");
        verify(pingObserver).onNext(pingCaptor.capture());
        PingResponse resp = pingCaptor.getValue();
        assertThat(resp.getAlive()).isTrue();
        assertThat(resp.getLatencyMs()).isGreaterThanOrEqualTo(0);
    }

    @Test
    void pingShouldReturnAliveFalseWhenConnectionNotFound() {
        PingRequest req = PingRequest.newBuilder().setConnectionId("bad").build();
        when(poolManager.getPool("bad")).thenReturn(null);

        service.ping(req, pingObserver);

        verify(metrics).recordGrpcRequest("Ping", "error");
        verify(pingObserver).onNext(pingCaptor.capture());
        PingResponse resp = pingCaptor.getValue();
        assertThat(resp.getAlive()).isFalse();
        assertThat(resp.getLatencyMs()).isEqualTo(-1);
    }

    private ConnectionRequest validRequest() {
        return ConnectionRequest.newBuilder()
                .setHost("localhost")
                .setPort(9088)
                .setDatabase("testdb")
                .setUsername("user")
                .setPassword("pass")
                .setPoolSize(5)
                .build();
    }
}
