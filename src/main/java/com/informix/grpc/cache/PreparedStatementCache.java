package com.informix.grpc.cache;

import java.sql.PreparedStatement;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public class PreparedStatementCache {

    private final Map<String, PreparedStatement> cache = new ConcurrentHashMap<>();
    private final AtomicLong idCounter = new AtomicLong(0);

    public String put(PreparedStatement stmt) {
        String id = "stmt_" + idCounter.incrementAndGet();
        cache.put(id, stmt);
        return id;
    }

    public PreparedStatement get(String id) {
        return cache.get(id);
    }

    public void removeAndClose(String id) {
        PreparedStatement stmt = cache.remove(id);
        if (stmt != null) {
            try { stmt.close(); } catch (Exception ignored) {}
            try { stmt.getConnection().close(); } catch (Exception ignored) {}
        }
    }

    public void closeAll() {
        cache.values().forEach(stmt -> {
            try { stmt.close(); } catch (Exception ignored) {}
            try { stmt.getConnection().close(); } catch (Exception ignored) {}
        });
        cache.clear();
    }
}
