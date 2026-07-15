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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class TransactionServiceTest {

    @Mock PoolManager poolManager;
    @Mock GrpcMetrics metrics;
    @Mock StreamObserver<TransactionResponse> txObserver;
    @Mock StreamObserver<CommitResponse> commitObserver;
    @Mock StreamObserver<RollbackResponse> rollbackObserver;
    @Captor ArgumentCaptor<TransactionResponse> txCaptor;
    @Captor ArgumentCaptor<CommitResponse> commitCaptor;
    @Captor ArgumentCaptor<RollbackResponse> rollbackCaptor;
    @Mock Histogram.Timer timer;

    private TransactionService service;

    @BeforeEach
    void setUp() {
        service = new TransactionService(poolManager, metrics);
        when(metrics.startGrpcTimer(any())).thenReturn(timer);
    }

    @Test
    void beginTransactionShouldSetAutoCommitFalse() throws Exception {
        TransactionRequest req = TransactionRequest.newBuilder()
                .setConnectionId("c1").setIsolationLevel("READ_COMMITTED").build();
        HikariDataSource ds = mock(HikariDataSource.class);
        Connection conn = mock(Connection.class);
        when(poolManager.getPool("c1")).thenReturn(ds);
        when(ds.getConnection()).thenReturn(conn);

        service.beginTransaction(req, txObserver);

        verify(conn).setAutoCommit(false);
        verify(conn).setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
        verify(metrics).incTransaction("begin");
        verify(metrics).recordGrpcRequest("BeginTransaction", "ok");
        verify(txObserver).onNext(argThat(TransactionResponse::getSuccess));

        assertThat(service.getActiveConnection("c1")).isSameAs(conn);
    }

    @Test
    void beginTransactionShouldDefaultToNoIsolationChange() throws Exception {
        TransactionRequest req = TransactionRequest.newBuilder().setConnectionId("c1").build();
        HikariDataSource ds = mock(HikariDataSource.class);
        Connection conn = mock(Connection.class);
        when(poolManager.getPool("c1")).thenReturn(ds);
        when(ds.getConnection()).thenReturn(conn);

        service.beginTransaction(req, txObserver);
        verify(conn, never()).setTransactionIsolation(anyInt());
    }

    @Test
    void commitShouldCommitAndClose() throws Exception {
        // Set up active transaction
        TransactionRequest req = TransactionRequest.newBuilder().setConnectionId("c1").build();
        HikariDataSource ds = mock(HikariDataSource.class);
        Connection conn = mock(Connection.class);
        when(poolManager.getPool("c1")).thenReturn(ds);
        when(ds.getConnection()).thenReturn(conn);
        service.beginTransaction(req, txObserver);

        service.commit(CommitRequest.newBuilder().setConnectionId("c1").build(), commitObserver);

        verify(conn).commit();
        verify(conn).setAutoCommit(true);
        verify(conn).close();
        verify(metrics).incTransaction("commit");
        verify(commitObserver).onNext(argThat(CommitResponse::getSuccess));
        assertThat(service.getActiveConnection("c1")).isNull();
    }

    @Test
    void rollbackShouldRollbackAndClose() throws Exception {
        TransactionRequest req = TransactionRequest.newBuilder().setConnectionId("c1").build();
        HikariDataSource ds = mock(HikariDataSource.class);
        Connection conn = mock(Connection.class);
        when(poolManager.getPool("c1")).thenReturn(ds);
        when(ds.getConnection()).thenReturn(conn);
        service.beginTransaction(req, txObserver);

        service.rollback(RollbackRequest.newBuilder().setConnectionId("c1").build(), rollbackObserver);

        verify(conn).rollback();
        verify(conn).setAutoCommit(true);
        verify(conn).close();
        verify(metrics).incTransaction("rollback");
        verify(rollbackObserver).onNext(argThat(RollbackResponse::getSuccess));
        assertThat(service.getActiveConnection("c1")).isNull();
    }

    @Test
    void beginTransactionShouldCloseConnectionWhenSetupFails() throws Exception {
        // Regression test: if setAutoCommit/setTransactionIsolation throws
        // before the connection is registered, nothing else will ever close
        // it - it would otherwise leak out of the pool.
        TransactionRequest req = TransactionRequest.newBuilder()
                .setConnectionId("c1").setIsolationLevel("SERIALIZABLE").build();
        HikariDataSource ds = mock(HikariDataSource.class);
        Connection conn = mock(Connection.class);
        when(poolManager.getPool("c1")).thenReturn(ds);
        when(ds.getConnection()).thenReturn(conn);
        doThrow(new java.sql.SQLException("not supported"))
                .when(conn).setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);

        service.beginTransaction(req, txObserver);

        verify(conn).close();
        assertThat(service.getActiveConnection("c1")).isNull();
        verify(metrics).recordGrpcRequest("BeginTransaction", "error");
    }

    @Test
    void commitShouldCloseConnectionEvenIfCommitFails() throws Exception {
        // Regression test: a failing commit() must not leak the connection.
        TransactionRequest req = TransactionRequest.newBuilder().setConnectionId("c1").build();
        HikariDataSource ds = mock(HikariDataSource.class);
        Connection conn = mock(Connection.class);
        when(poolManager.getPool("c1")).thenReturn(ds);
        when(ds.getConnection()).thenReturn(conn);
        service.beginTransaction(req, txObserver);
        doThrow(new java.sql.SQLException("commit failed")).when(conn).commit();

        service.commit(CommitRequest.newBuilder().setConnectionId("c1").build(), commitObserver);

        verify(conn).close();
        verify(metrics).recordGrpcRequest("Commit", "error");
    }

    @Test
    void rollbackShouldCloseConnectionEvenIfRollbackFails() throws Exception {
        // Regression test: a failing rollback() must not leak the connection.
        TransactionRequest req = TransactionRequest.newBuilder().setConnectionId("c1").build();
        HikariDataSource ds = mock(HikariDataSource.class);
        Connection conn = mock(Connection.class);
        when(poolManager.getPool("c1")).thenReturn(ds);
        when(ds.getConnection()).thenReturn(conn);
        service.beginTransaction(req, txObserver);
        doThrow(new java.sql.SQLException("rollback failed")).when(conn).rollback();

        service.rollback(RollbackRequest.newBuilder().setConnectionId("c1").build(), rollbackObserver);

        verify(conn).close();
        verify(metrics).recordGrpcRequest("Rollback", "error");
    }

    @Test
    void discardActiveConnectionShouldCloseAndRemoveConnection() throws Exception {
        TransactionRequest req = TransactionRequest.newBuilder().setConnectionId("c1").build();
        HikariDataSource ds = mock(HikariDataSource.class);
        Connection conn = mock(Connection.class);
        when(poolManager.getPool("c1")).thenReturn(ds);
        when(ds.getConnection()).thenReturn(conn);
        service.beginTransaction(req, txObserver);

        service.discardActiveConnection("c1");

        verify(conn).close();
        assertThat(service.getActiveConnection("c1")).isNull();

        // No-op (and no exception) when there is nothing to discard.
        assertThatCode(() -> service.discardActiveConnection("missing")).doesNotThrowAnyException();
    }

    @Test
    void commitShouldFailIfNoActiveTransaction() {
        service.commit(CommitRequest.newBuilder().setConnectionId("c1").build(), commitObserver);

        verify(metrics).recordGrpcRequest("Commit", "error");
        verify(commitObserver).onNext(argThat(r -> !r.getSuccess() && r.getError().contains("No active")));
    }

    @Test
    void rollbackShouldFailIfNoActiveTransaction() {
        service.rollback(RollbackRequest.newBuilder().setConnectionId("c1").build(), rollbackObserver);

        verify(metrics).recordGrpcRequest("Rollback", "error");
        verify(rollbackObserver).onNext(argThat(r -> !r.getSuccess() && r.getError().contains("No active")));
    }
}
