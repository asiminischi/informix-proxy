package com.informix.grpc.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.informix.grpc.*;
import com.informix.grpc.cache.PreparedStatementCache;
import com.informix.grpc.metrics.GrpcMetrics;
import com.informix.grpc.pool.PoolManager;
import com.zaxxer.hikari.HikariDataSource;
import io.grpc.stub.StreamObserver;
import io.prometheus.client.Histogram;

import java.sql.*;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PreparedStatementServiceTest {

    @Mock PoolManager poolManager;
    @Mock PreparedStatementCache stmtCache;
    @Mock GrpcMetrics metrics;
    @Mock StreamObserver<PrepareResponse> prepareObserver;
    @Mock StreamObserver<QueryResponse> queryObserver;
    @Mock StreamObserver<ClosePreparedResponse> closeObserver;
    @Captor ArgumentCaptor<PrepareResponse> prepareCaptor;
    @Captor ArgumentCaptor<QueryResponse> queryCaptor;
    @Captor ArgumentCaptor<ClosePreparedResponse> closeCaptor;
    @Mock Histogram.Timer timer;

    private PreparedStatementService service;

    @BeforeEach
    void setUp() {
        service = new PreparedStatementService(poolManager, stmtCache, metrics);
        when(metrics.startGrpcTimer(any())).thenReturn(timer);
    }

    @Test
    void prepareShouldReturnStatementId() throws Exception {
        PrepareRequest req = PrepareRequest.newBuilder().setConnectionId("c1").setSql("SELECT ?").build();
        HikariDataSource ds = mock(HikariDataSource.class);
        Connection conn = mock(Connection.class);
        PreparedStatement pstmt = mock(PreparedStatement.class);
        ParameterMetaData paramMeta = mock(ParameterMetaData.class);

        when(poolManager.getPool("c1")).thenReturn(ds);
        when(ds.getConnection()).thenReturn(conn);
        when(conn.prepareStatement("SELECT ?")).thenReturn(pstmt);
        when(pstmt.getParameterMetaData()).thenReturn(paramMeta);
        when(paramMeta.getParameterCount()).thenReturn(1);
        when(stmtCache.put("c1", pstmt)).thenReturn("stmt_1");

        service.prepareStatement(req, prepareObserver);

        verify(metrics).recordGrpcRequest("PrepareStatement", "ok");
        verify(prepareObserver).onNext(prepareCaptor.capture());
        PrepareResponse resp = prepareCaptor.getValue();
        assertThat(resp.getStatementId()).isEqualTo("stmt_1");
        assertThat(resp.getParameterCount()).isEqualTo(1);
    }

    @Test
    void prepareShouldCloseConnectionAndSkipCachingWhenSetupFails() throws Exception {
        // Regression test: if a fallible setup step (here, reading parameter
        // metadata) throws after the statement was already prepared, the
        // connection must still be closed and the statement must never be
        // cached under an id the client will never receive - otherwise both
        // leak for the life of the JVM.
        PrepareRequest req = PrepareRequest.newBuilder().setConnectionId("c1").setSql("SELECT ?").build();
        HikariDataSource ds = mock(HikariDataSource.class);
        Connection conn = mock(Connection.class);
        PreparedStatement pstmt = mock(PreparedStatement.class);

        when(poolManager.getPool("c1")).thenReturn(ds);
        when(ds.getConnection()).thenReturn(conn);
        when(conn.prepareStatement("SELECT ?")).thenReturn(pstmt);
        when(pstmt.getParameterMetaData()).thenThrow(new SQLException("driver error"));

        service.prepareStatement(req, prepareObserver);

        verify(conn).close();
        verify(stmtCache, never()).put(any(), any());
        verify(metrics).recordGrpcRequest("PrepareStatement", "error");
        verify(prepareObserver).onNext(argThat(r -> r.getError().contains("driver error")));
    }

    @Test
    void prepareShouldReturnErrorWhenPoolMissing() {
        PrepareRequest req = PrepareRequest.newBuilder().setConnectionId("bad").setSql("X").build();
        when(poolManager.getPool("bad")).thenReturn(null);

        service.prepareStatement(req, prepareObserver);

        verify(metrics).recordGrpcRequest("PrepareStatement", "error");
        verify(metrics).incQueryError();
        verify(prepareObserver).onNext(argThat(r -> r.getError().contains("not found")));
    }

    @Test
    void executePreparedShouldStreamResults() throws Exception {
        ExecutePreparedRequest req = ExecutePreparedRequest.newBuilder()
                .setStatementId("stmt_1").setFetchSize(1).build();

        PreparedStatement pstmt = mock(PreparedStatement.class);
        ResultSet rs = mock(ResultSet.class);
        ResultSetMetaData meta = mock(ResultSetMetaData.class);

        when(stmtCache.get("stmt_1")).thenReturn(pstmt);
        when(pstmt.executeQuery()).thenReturn(rs);
        when(rs.getMetaData()).thenReturn(meta);
        when(meta.getColumnCount()).thenReturn(1);
        when(meta.getColumnName(1)).thenReturn("x");
        when(meta.getColumnType(1)).thenReturn(Types.INTEGER);
        when(meta.getColumnTypeName(1)).thenReturn("INTEGER");
        when(meta.getPrecision(1)).thenReturn(0);
        when(meta.getScale(1)).thenReturn(0);
        when(meta.isNullable(1)).thenReturn(ResultSetMetaData.columnNoNulls);
        when(rs.next()).thenReturn(true, false);
        when(rs.getObject(1)).thenReturn(99);
        when(rs.wasNull()).thenReturn(false);
        when(rs.getInt(1)).thenReturn(99);

        service.executePrepared(req, queryObserver);

        verify(metrics).incQuery("prepared");
        verify(metrics).recordGrpcRequest("ExecutePrepared", "ok");

        // Two responses: one batch, one final
        verify(queryObserver, times(2)).onNext(queryCaptor.capture());
        List<QueryResponse> responses = queryCaptor.getAllValues();
        assertThat(responses).hasSize(2);

        QueryResponse first = responses.get(0);
        assertThat(first.getHasMore()).isTrue();
        assertThat(first.getRowsCount()).isEqualTo(1);          // one row in this chunk
        assertThat(first.getColumnsCount()).isEqualTo(1);

        QueryResponse second = responses.get(1);
        assertThat(second.getHasMore()).isFalse();
        assertThat(second.getTotalRows()).isEqualTo(1);         // total rows across all chunks
        assertThat(second.getRowsList()).isEmpty();             // no rows in final chunk
    } 

    @Test
    void closePreparedShouldRemoveFromCache() {
        ClosePreparedRequest req = ClosePreparedRequest.newBuilder().setStatementId("stmt_1").build();
        service.closePrepared(req, closeObserver);

        verify(stmtCache).removeAndClose("stmt_1");
        verify(metrics).recordGrpcRequest("ClosePrepared", "ok");
        verify(closeObserver).onNext(argThat(ClosePreparedResponse::getSuccess));
    }
}
