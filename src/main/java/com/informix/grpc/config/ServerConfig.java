package com.informix.grpc.config;

public class ServerConfig {
    private final int grpcPort;
    private final int metricsPort;
    private final int healthPort;

    private ServerConfig(int grpcPort, int metricsPort, int healthPort) {
        this.grpcPort = grpcPort;
        this.metricsPort = metricsPort;
        this.healthPort = healthPort;
    }

    public static ServerConfig fromEnv() {
        int grpc = Integer.parseInt(System.getenv().getOrDefault("GRPC_PORT", "50051"));
        int metrics = Integer.parseInt(System.getenv().getOrDefault("METRICS_PORT", "9090"));
        int health = Integer.parseInt(System.getenv().getOrDefault("HEALTH_PORT", "8080"));
        return new ServerConfig(grpc, metrics, health);
    }

    public int getGrpcPort() { return grpcPort; }
    public int getMetricsPort() { return metricsPort; }
    public int getHealthPort() { return healthPort; }
}
