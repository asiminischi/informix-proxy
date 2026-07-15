package com.informix.grpc.cache;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public class PreparedStatementCache {

    private static final class Entry {
        final String connectionId;
        final PreparedStatement statement;

        Entry(String connectionId, PreparedStatement statement) {
            this.connectionId = connectionId;
            this.statement = statement;
        }
    }

    private final Map<String, Entry> cache = new ConcurrentHashMap<>();
    private final AtomicLong idCounter = new AtomicLong(0);

    public String put(String connectionId, PreparedStatement stmt) {
        String id = "stmt_" + idCounter.incrementAndGet();
        cache.put(id, new Entry(connectionId, stmt));
        return id;
    }

    public PreparedStatement get(String id) {
        Entry entry = cache.get(id);
        return entry != null ? entry.statement : null;
    }

    public void removeAndClose(String id) {
        Entry entry = cache.remove(id);
        if (entry != null) {
            closeQuietly(entry.statement);
        }
    }

    /**
     * Closes and removes every prepared statement that belongs to the given
     * connection id. Called when a client disconnects so statements it never
     * explicitly closed don't sit in the cache holding a JDBC connection open
     * for the lifetime of the JVM.
     */
    public void removeAllForConnection(String connectionId) {
        cache.entrySet().removeIf(e -> {
            if (!e.getValue().connectionId.equals(connectionId)) {
                return false;
            }
            closeQuietly(e.getValue().statement);
            return true;
        });
    }

    public void closeAll() {
        cache.values().forEach(entry -> closeQuietly(entry.statement));
        cache.clear();
    }

    private static void closeQuietly(PreparedStatement stmt) {
        Connection conn = null;
        try { conn = stmt.getConnection(); } catch (Exception ignored) {}
        try { stmt.close(); } catch (Exception ignored) {}
        if (conn != null) {
            try { conn.close(); } catch (Exception ignored) {}
        }
    }
}
