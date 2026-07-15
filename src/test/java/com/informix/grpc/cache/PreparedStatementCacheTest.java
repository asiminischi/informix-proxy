package com.informix.grpc.cache;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.sql.PreparedStatement;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PreparedStatementCacheTest {

    @Mock PreparedStatement stmt;
    private PreparedStatementCache cache;

    @BeforeEach
    void setUp() {
        cache = new PreparedStatementCache();
    }

    @Test
    void shouldStoreAndRetrieveStatement() {
        String id = cache.put("conn_1", stmt);
        assertThat(id).startsWith("stmt_");
        assertThat(cache.get(id)).isSameAs(stmt);
    }

    @Test
    void shouldReturnNullForUnknownId() {
        assertThat(cache.get("nonexistent")).isNull();
    }

    @Test
    void shouldCloseStatementOnRemove() throws Exception {
        String id = cache.put("conn_1", stmt);
        cache.removeAndClose(id);

        verify(stmt).close();
        assertThat(cache.get(id)).isNull();
    }

    @Test
    void shouldCloseAllStatements() throws Exception {
        PreparedStatement other = mock(PreparedStatement.class);
        cache.put("conn_1", stmt);
        cache.put("conn_2", other);

        cache.closeAll();

        verify(stmt).close();
        verify(other).close();
    }

    @Test
    void shouldCloseAndRemoveOnlyStatementsForGivenConnection() throws Exception {
        PreparedStatement otherConnStmt = mock(PreparedStatement.class);
        String id1 = cache.put("conn_1", stmt);
        String id2 = cache.put("conn_2", otherConnStmt);

        cache.removeAllForConnection("conn_1");

        verify(stmt).close();
        verify(otherConnStmt, never()).close();
        assertThat(cache.get(id1)).isNull();
        assertThat(cache.get(id2)).isSameAs(otherConnStmt);
    }

    @Test
    void shouldAlsoCloseUnderlyingConnectionOnRemove() throws Exception {
        java.sql.Connection conn = mock(java.sql.Connection.class);
        when(stmt.getConnection()).thenReturn(conn);

        String id = cache.put("conn_1", stmt);
        cache.removeAndClose(id);

        verify(conn).close();
    }
}
