package com.informix.grpc.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.informix.grpc.*;
import com.informix.grpc.cache.PreparedStatementCache;
import com.informix.grpc.metrics.GrpcMetrics;
import com.informix.grpc.pool.PoolManager;
import com.zaxxer.hikari.HikariDataSource;
import io.grpc.stub.StreamObserver;
import io.prometheus.client.Histogram;

import java.sql.*;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class QueryServiceTest {

    @Mock PoolManager poolManager;
    @Mock PreparedStatementCache stmtCache;
    @Mock TransactionService transactionService;
    @Mock GrpcMetrics metrics;
    @Mock StreamObserver<QueryResponse> queryObserver;
    @Mock StreamObserver<UpdateResponse> updateObserver;
    @Mock StreamObserver<BatchResponse> batchObserver;
    @Captor ArgumentCaptor<QueryResponse> queryCaptor;
    @Captor ArgumentCaptor<UpdateResponse> updateCaptor;
    @Captor ArgumentCaptor<BatchResponse> batchCaptor;
    @Mock Histogram.Timer timer;

    private QueryService service;

    @BeforeEach
    void setUp() {
        service = new QueryService(poolManager, stmtCache, transactionService, metrics);
        when(metrics.startGrpcTimer(any())).thenReturn(timer);
    }

    // --------------- executeQuery ---------------

    @Test
    void executeQueryShouldStreamRows() throws Exception {
        QueryRequest req = QueryRequest.newBuilder()
                .setConnectionId("c1").setSql("SELECT id, name FROM t").setFetchSize(2).build();

        HikariDataSource ds = mock(HikariDataSource.class);
        Connection conn = mock(Connection.class);
        PreparedStatement pstmt = mock(PreparedStatement.class);
        ResultSet rs = mock(ResultSet.class);
        ResultSetMetaData meta = mock(ResultSetMetaData.class);

        when(poolManager.getPool("c1")).thenReturn(ds);
        when(ds.getConnection()).thenReturn(conn);
        when(conn.prepareStatement("SELECT id, name FROM t")).thenReturn(pstmt);
        when(pstmt.executeQuery()).thenReturn(rs);
        when(rs.getMetaData()).thenReturn(meta);
        when(meta.getColumnCount()).thenReturn(2);
        when(meta.getColumnName(1)).thenReturn("id");
        when(meta.getColumnType(1)).thenReturn(Types.INTEGER);
        when(meta.getColumnTypeName(1)).thenReturn("INTEGER");
        when(meta.getPrecision(1)).thenReturn(9);
        when(meta.getScale(1)).thenReturn(0);
        when(meta.isNullable(1)).thenReturn(ResultSetMetaData.columnNoNulls);
        when(meta.getColumnName(2)).thenReturn("name");
        when(meta.getColumnType(2)).thenReturn(Types.VARCHAR);
        when(meta.getColumnTypeName(2)).thenReturn("VARCHAR");
        when(meta.getPrecision(2)).thenReturn(50);
        when(meta.getScale(2)).thenReturn(0);
        when(meta.isNullable(2)).thenReturn(ResultSetMetaData.columnNullable);

        when(rs.next()).thenReturn(true, true, true, false);
        when(rs.getObject(1)).thenReturn(1, 2, 3);
        when(rs.wasNull()).thenReturn(false);
        when(rs.getInt(1)).thenReturn(1, 2, 3);
        when(rs.getObject(2)).thenReturn("Alice", "Bob", "Charlie");
        when(rs.getString(2)).thenReturn("Alice", "Bob", "Charlie");

        service.executeQuery(req, queryObserver);

        verify(queryObserver, times(2)).onNext(queryCaptor.capture());
        List<QueryResponse> responses = queryCaptor.getAllValues();
        assertThat(responses).hasSize(2);

        QueryResponse first = responses.get(0);
        assertThat(first.getHasMore()).isTrue();
        assertThat(first.getRowsCount()).isEqualTo(2);
        assertThat(first.getColumnsCount()).isEqualTo(2);
        assertThat(first.getRows(0).getValues(0).getIntData()).isEqualTo(1);
        assertThat(first.getRows(0).getValues(1).getStringData()).isEqualTo("Alice");

        QueryResponse second = responses.get(1);
        assertThat(second.getHasMore()).isFalse();
        assertThat(second.getRowsCount()).isEqualTo(1);
        assertThat(second.getColumnsCount()).isZero();
        assertThat(second.getRows(0).getValues(0).getIntData()).isEqualTo(3);

        verify(metrics).incQuery("query");
        verify(metrics).recordGrpcRequest("ExecuteQuery", "ok");
    }

    @Test
    void executeQueryShouldUseTransactionalConnectionIfActive() throws Exception {
        QueryRequest req = QueryRequest.newBuilder().setConnectionId("c1").setSql("SELECT 1").build();

        Connection txConn = mock(Connection.class);
        PreparedStatement pstmt = mock(PreparedStatement.class);
        ResultSet rs = mock(ResultSet.class);
        when(transactionService.getActiveConnection("c1")).thenReturn(txConn);
        when(txConn.prepareStatement("SELECT 1")).thenReturn(pstmt);
        when(pstmt.executeQuery()).thenReturn(rs);
        when(rs.getMetaData()).thenReturn(mock(ResultSetMetaData.class));
        when(rs.next()).thenReturn(false);

        service.executeQuery(req, queryObserver);

        verify(poolManager, never()).getPool(any());
        verify(transactionService).getActiveConnection("c1");
    }

    @Test
    void executeQueryShouldReturnErrorWhenPoolMissing() {
        QueryRequest req = QueryRequest.newBuilder().setConnectionId("bad").setSql("SELECT 1").build();
        when(poolManager.getPool("bad")).thenReturn(null);

        service.executeQuery(req, queryObserver);

        verify(metrics).incQueryError();
        verify(metrics).recordGrpcRequest("ExecuteQuery", "error");
        verify(queryObserver).onNext(argThat(r -> r.getError().contains("not found")));
    }

    // --------------- executeUpdate ---------------

    @Test
    void executeUpdateShouldReturnRowsAffected() throws Exception {
        UpdateRequest req = UpdateRequest.newBuilder()
                .setConnectionId("c1").setSql("DELETE FROM t WHERE id=?").build();

        HikariDataSource ds = mock(HikariDataSource.class);
        Connection conn = mock(Connection.class);
        PreparedStatement pstmt = mock(PreparedStatement.class);

        when(poolManager.getPool("c1")).thenReturn(ds);
        when(ds.getConnection()).thenReturn(conn);
        when(conn.prepareStatement(anyString())).thenReturn(pstmt);
        when(pstmt.executeUpdate()).thenReturn(5);

        service.executeUpdate(req, updateObserver);

        verify(metrics).incQuery("update");
        verify(metrics).recordGrpcRequest("ExecuteUpdate", "ok");
        verify(updateObserver).onNext(updateCaptor.capture());
        assertThat(updateCaptor.getValue().getRowsAffected()).isEqualTo(5);
    }

    @Test
    void executeUpdateShouldCloseConnWhenNotTransactional() throws Exception {
        UpdateRequest req = UpdateRequest.newBuilder().setConnectionId("c1").setSql("DELETE FROM t").build();
        HikariDataSource ds = mock(HikariDataSource.class);
        Connection conn = mock(Connection.class);
        PreparedStatement pstmt = mock(PreparedStatement.class);
        when(poolManager.getPool("c1")).thenReturn(ds);
        when(ds.getConnection()).thenReturn(conn);
        when(conn.prepareStatement(anyString())).thenReturn(pstmt);
        when(pstmt.executeUpdate()).thenReturn(1);

        service.executeUpdate(req, updateObserver);

        verify(conn).close();
    }

    // --------------- executeBatch ---------------

    @Test
    void executeBatchShouldReturnArrayOfAffectedRows() throws Exception {
        BatchRequest req = BatchRequest.newBuilder()
                .setConnectionId("c1")
                .addSqlStatements("INSERT INTO t VALUES(1)")
                .addSqlStatements("INSERT INTO t VALUES(2)")
                .build();

        HikariDataSource ds = mock(HikariDataSource.class);
        Connection conn = mock(Connection.class);
        Statement stmt = mock(Statement.class);
        when(poolManager.getPool("c1")).thenReturn(ds);
        when(ds.getConnection()).thenReturn(conn);
        when(conn.createStatement()).thenReturn(stmt);
        when(stmt.executeBatch()).thenReturn(new int[]{1, 1});

        service.executeBatch(req, batchObserver);

        verify(metrics).incQuery("batch");
        verify(metrics).recordGrpcRequest("ExecuteBatch", "ok");
        verify(batchObserver).onNext(batchCaptor.capture());
        assertThat(batchCaptor.getValue().getRowsAffectedList()).containsExactly(1, 1);
    }
}
