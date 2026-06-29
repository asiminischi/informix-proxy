package com.informix.grpc.config;

import java.util.concurrent.TimeUnit;

public class PoolConfig {

    private final int maxPoolSize;
    private final int minIdle;
    private final long connectionTimeoutMs;
    private final long idleTimeoutMs;
    private final long maxLifetimeMs;
    private final String connectionTestQuery;

    private PoolConfig(int maxPoolSize, int minIdle, long connectionTimeoutMs,
                       long idleTimeoutMs, long maxLifetimeMs, String connectionTestQuery) {
        this.maxPoolSize = maxPoolSize;
        this.minIdle = minIdle;
        this.connectionTimeoutMs = connectionTimeoutMs;
        this.idleTimeoutMs = idleTimeoutMs;
        this.maxLifetimeMs = maxLifetimeMs;
        this.connectionTestQuery = connectionTestQuery;
    }

    public static PoolConfig fromEnv() {
        int maxPoolSize = Integer.parseInt(envOrDefault("POOL_MAX_SIZE", "20"));
        int minIdle = Integer.parseInt(envOrDefault("POOL_MIN_IDLE", "5"));
        long connTimeout = Long.parseLong(envOrDefault("POOL_CONNECTION_TIMEOUT_MS", "30000"));
        long idleTimeout = Long.parseLong(envOrDefault("POOL_IDLE_TIMEOUT_MS", "600000"));
        long maxLifetime = Long.parseLong(envOrDefault("POOL_MAX_LIFETIME_MS", "1800000"));
        String testQuery = envOrDefault("POOL_CONNECTION_TEST_QUERY", "SELECT 1 FROM systables WHERE tabid = 1");
        return new PoolConfig(maxPoolSize, minIdle, connTimeout, idleTimeout, maxLifetime, testQuery);
    }

    public int getMaxPoolSize() { return maxPoolSize; }
    public int getMinIdle() { return minIdle; }
    public long getConnectionTimeoutMs() { return connectionTimeoutMs; }
    public long getIdleTimeoutMs() { return idleTimeoutMs; }
    public long getMaxLifetimeMs() { return maxLifetimeMs; }
    public String getConnectionTestQuery() { return connectionTestQuery; }

    private static String envOrDefault(String key, String defaultValue) {
        String val = System.getenv(key);
        return (val != null && !val.isEmpty()) ? val : defaultValue;
    }
}
