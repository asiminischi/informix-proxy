package com.informix.grpc.metrics;

import io.prometheus.client.Counter;
import io.prometheus.client.Gauge;
import io.prometheus.client.Histogram;

public class GrpcMetrics {

    private final Histogram requestLatency;
    private final Counter requestCount;
    private final Gauge activeConnections;
    private final Counter queryCounter;
    private final Counter queryErrorCounter;
    private final Counter transactionCounter;

    public GrpcMetrics() {
        requestLatency = Histogram.build()
                .name("grpc_server_handling_seconds")
                .help("gRPC request handling duration in seconds")
                .labelNames("method")
                .buckets(0.001, 0.005, 0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1.0, 2.5, 5.0, 10.0)
                .register();

        requestCount = Counter.build()
                .name("grpc_server_handled_total")
                .help("Total gRPC requests by method and status")
                .labelNames("method", "status")
                .register();

        activeConnections = Gauge.build()
                .name("informix_connections_active")
                .help("Number of active client connection pools")
                .register();

        queryCounter = Counter.build()
                .name("informix_queries_total")
                .help("Total queries executed")
                .labelNames("type")
                .register();

        queryErrorCounter = Counter.build()
                .name("informix_query_errors_total")
                .help("Total query errors")
                .register();

        transactionCounter = Counter.build()
                .name("informix_transactions_total")
                .help("Total transactions")
                .labelNames("type")
                .register();
    }

    public Histogram.Timer startGrpcTimer(String method) {
        return requestLatency.labels(method).startTimer();
    }

    public void recordGrpcRequest(String method, String status) {
        requestCount.labels(method, status).inc();
    }

    public void incActiveConnections() { activeConnections.inc(); }
    public void decActiveConnections() { activeConnections.dec(); }
    public void incQuery(String type) { queryCounter.labels(type).inc(); }
    public void incQueryError() { queryErrorCounter.inc(); }
    public void incTransaction(String type) { transactionCounter.labels(type).inc(); }
}
