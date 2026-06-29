package com.informix.grpc.pool;

import com.informix.grpc.ConnectionRequest;
import com.informix.grpc.config.PoolConfig;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class PoolManager {
    private final Map<String, HikariDataSource> pools = new ConcurrentHashMap<>();
    private final PoolConfig poolConfig;

    public PoolManager(PoolConfig poolConfig) {
        this.poolConfig = poolConfig;
    }

    public String createPool(ConnectionRequest request) throws Exception {
        String connectionId = "conn_" + System.nanoTime();

        String jdbcUrl = buildJdbcUrl(request);
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(jdbcUrl);
        config.setUsername(request.getUsername());
        config.setPassword(request.getPassword());
        config.setDriverClassName("com.informix.jdbc.IfxDriver");

        // Use PoolConfig values, falling back to request.getPoolSize() if specified
        int poolSize = request.getPoolSize() > 0 ? request.getPoolSize() : poolConfig.getMaxPoolSize();
        config.setMaximumPoolSize(poolSize);
        config.setMinimumIdle(poolConfig.getMinIdle());
        config.setConnectionTimeout(poolConfig.getConnectionTimeoutMs());
        config.setIdleTimeout(poolConfig.getIdleTimeoutMs());
        config.setMaxLifetime(poolConfig.getMaxLifetimeMs());
        config.setConnectionTestQuery(poolConfig.getConnectionTestQuery());

        HikariDataSource ds = new HikariDataSource(config);

        // Validate immediately
        try (Connection conn = ds.getConnection()) {
            DatabaseMetaData meta = conn.getMetaData();
            // version not needed here
        }

        pools.put(connectionId, ds);
        return connectionId;
    }
    
    public HikariDataSource getPool(String connectionId) {
        return pools.get(connectionId);
    }

    public HikariDataSource removePool(String connectionId) {
        HikariDataSource ds = pools.remove(connectionId);
        if (ds != null) {
            ds.close();
        }
        return ds;
    }

    public void shutdown() {
        pools.values().forEach(HikariDataSource::close);
        pools.clear();
    }

    public Map<String, HikariDataSource> getPools() {
        return pools; // for PoolStatsCollector
    }

    private String buildJdbcUrl(ConnectionRequest request) {
        String base = String.format("jdbc:informix-sqli://%s:%d/%s",
                request.getHost(), request.getPort(), request.getDatabase());

        if (request.getPropertiesMap().isEmpty()) {
            return base;
        }

        StringBuilder sb = new StringBuilder(base);
        boolean first = true;
        for (Map.Entry<String, String> entry : request.getPropertiesMap().entrySet()) {
            sb.append(first ? ":" : ";")
              .append(entry.getKey()).append("=").append(entry.getValue());
            first = false;
        }
        return sb.toString();
    }
}
