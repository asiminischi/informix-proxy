package com.informix.grpc.pool;

import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import io.prometheus.client.Collector;
import io.prometheus.client.GaugeMetricFamily;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Custom Prometheus collector that reads HikariCP pool statistics at scrape time.
 */
public class PoolStatsCollector extends Collector {
    private final Map<String, HikariDataSource> pools;

    public PoolStatsCollector(Map<String, HikariDataSource> pools) {
        this.pools = pools;
    }

    @Override
    public List<MetricFamilySamples> collect() {
        List<MetricFamilySamples> mfs = new ArrayList<>();
        int active = 0, idle = 0, total = 0, pending = 0;

        for (HikariDataSource ds : pools.values()) {
            try {
                HikariPoolMXBean bean = ds.getHikariPoolMXBean();
                if (bean != null) {
                    active += bean.getActiveConnections();
                    idle += bean.getIdleConnections();
                    total += bean.getTotalConnections();
                    pending += bean.getThreadsAwaitingConnection();
                }
            } catch (Exception ignored) {
            }
        }

        mfs.add(new GaugeMetricFamily(
                "informix_pool_active_connections",
                "Active JDBC connections across all pools", active));
        mfs.add(new GaugeMetricFamily(
                "informix_pool_idle_connections",
                "Idle JDBC connections across all pools", idle));
        mfs.add(new GaugeMetricFamily(
                "informix_pool_total_connections",
                "Total JDBC connections across all pools", total));
        mfs.add(new GaugeMetricFamily(
                "informix_pool_pending_threads",
                "Threads waiting for a JDBC connection", pending));

        return mfs;
    }
}
