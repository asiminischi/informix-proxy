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
        String id = cache.put(stmt);
        assertThat(id).startsWith("stmt_");
        assertThat(cache.get(id)).isSameAs(stmt);
    }

    @Test
    void shouldReturnNullForUnknownId() {
        assertThat(cache.get("nonexistent")).isNull();
    }

    @Test
    void shouldCloseStatementOnRemove() throws Exception {
        String id = cache.put(stmt);
        cache.removeAndClose(id);

        verify(stmt).close();
        assertThat(cache.get(id)).isNull();
    }

    @Test
    void shouldCloseAllStatements() throws Exception {
        PreparedStatement other = mock(PreparedStatement.class);
        cache.put(stmt);
        cache.put(other);

        cache.closeAll();

        verify(stmt).close();
        verify(other).close();
    }
}
