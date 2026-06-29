package com.informix.grpc;

import com.informix.grpc.cache.PreparedStatementCache;
import com.informix.grpc.config.ServerConfig;
import com.informix.grpc.health.HealthServer;
import com.informix.grpc.metrics.GrpcMetrics;
import com.informix.grpc.pool.PoolManager;
import com.informix.grpc.pool.PoolStatsCollector;
import com.informix.grpc.service.*;
import com.informix.grpc.config.PoolConfig;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.stub.StreamObserver;
import io.prometheus.client.exporter.HTTPServer;
import io.prometheus.client.hotspot.DefaultExports;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.TimeUnit;

public class InformixProxyServer extends InformixServiceGrpc.InformixServiceImplBase {

    private final ConnectionService connectionService;
    private final QueryService queryService;
    private final PreparedStatementService preparedStatementService;
    private final TransactionService transactionService;
    private final MetadataService metadataService;

    private static final Logger logger = LoggerFactory.getLogger(InformixProxyServer.class);

    public InformixProxyServer(ConnectionService connectionService,
                               QueryService queryService,
                               PreparedStatementService preparedStatementService,
                               TransactionService transactionService,
                               MetadataService metadataService) {
        this.connectionService = connectionService;
        this.queryService = queryService;
        this.preparedStatementService = preparedStatementService;
        this.transactionService = transactionService;
        this.metadataService = metadataService;
    }

    // --- gRPC method dispatchers ---

    @Override
    public void connect(ConnectionRequest request, StreamObserver<ConnectionResponse> responseObserver) {
        connectionService.connect(request, responseObserver);
    }

    @Override
    public void disconnect(DisconnectRequest request, StreamObserver<DisconnectResponse> responseObserver) {
        connectionService.disconnect(request, responseObserver);
    }

    @Override
    public void ping(PingRequest request, StreamObserver<PingResponse> responseObserver) {
        connectionService.ping(request, responseObserver);
    }

    @Override
    public void executeQuery(QueryRequest request, StreamObserver<QueryResponse> responseObserver) {
        queryService.executeQuery(request, responseObserver);
    }

    @Override
    public void executeUpdate(UpdateRequest request, StreamObserver<UpdateResponse> responseObserver) {
        queryService.executeUpdate(request, responseObserver);
    }

    @Override
    public void executeBatch(BatchRequest request, StreamObserver<BatchResponse> responseObserver) {
        queryService.executeBatch(request, responseObserver);
    }

    @Override
    public void prepareStatement(PrepareRequest request, StreamObserver<PrepareResponse> responseObserver) {
        preparedStatementService.prepareStatement(request, responseObserver);
    }

    @Override
    public void executePrepared(ExecutePreparedRequest request, StreamObserver<QueryResponse> responseObserver) {
        preparedStatementService.executePrepared(request, responseObserver);
    }

    @Override
    public void closePrepared(ClosePreparedRequest request, StreamObserver<ClosePreparedResponse> responseObserver) {
        preparedStatementService.closePrepared(request, responseObserver);
    }

    @Override
    public void beginTransaction(TransactionRequest request, StreamObserver<TransactionResponse> responseObserver) {
        transactionService.beginTransaction(request, responseObserver);
    }

    @Override
    public void commit(CommitRequest request, StreamObserver<CommitResponse> responseObserver) {
        transactionService.commit(request, responseObserver);
    }

    @Override
    public void rollback(RollbackRequest request, StreamObserver<RollbackResponse> responseObserver) {
        transactionService.rollback(request, responseObserver);
    }

    @Override
    public void getMetadata(MetadataRequest request, StreamObserver<MetadataResponse> responseObserver) {
        metadataService.getMetadata(request, responseObserver);
    }

    // Application entry point 
    public static void main(String[] args) throws Exception {
        ServerConfig config = ServerConfig.fromEnv();

        DefaultExports.initialize();
        GrpcMetrics metrics = new GrpcMetrics();

        PoolConfig poolConfig = PoolConfig.fromEnv();
        PoolManager poolManager = new PoolManager(poolConfig);
        PreparedStatementCache stmtCache = new PreparedStatementCache();

        TransactionService transactionService = new TransactionService(poolManager, metrics);
        ConnectionService connectionService = new ConnectionService(poolManager, metrics);
        QueryService queryService = new QueryService(poolManager, stmtCache, transactionService, metrics);
        PreparedStatementService preparedService = new PreparedStatementService(poolManager, stmtCache, metrics);
        MetadataService metadataService = new MetadataService(poolManager, metrics);

        new PoolStatsCollector(poolManager.getPools()).register();

        HTTPServer metricsServer = new HTTPServer.Builder()
                .withPort(config.getMetricsPort())
                .build();
        logger.info("Metrics server on port " + config.getMetricsPort());

        HealthServer healthServer = new HealthServer(config.getHealthPort());
        healthServer.start();

        // gRPC Health - disabled until grpc-services dependency is fixed
        // HealthStatusManager healthStatusManager = new HealthStatusManager();
        // healthStatusManager.setStatus("", ServingStatus.SERVING);

        InformixProxyServer serviceImpl = new InformixProxyServer(
                connectionService, queryService, preparedService, transactionService, metadataService);
        Server grpcServer = ServerBuilder.forPort(config.getGrpcPort())
                // .addService(healthStatusManager.getHealthService())
                .addService(serviceImpl)
                .maxInboundMessageSize(50 * 1024 * 1024)
                .build()
                .start();

        logger.info("Informix gRPC Proxy started on port " + config.getGrpcPort());

        // Graceful shutdown 
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            logger.info("Shutting down...");
            healthServer.stop();
            grpcServer.shutdown();
            try {
                grpcServer.awaitTermination(30, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            poolManager.shutdown();
            stmtCache.closeAll();
            metricsServer.close();
        }));

        grpcServer.awaitTermination();
    }
}
