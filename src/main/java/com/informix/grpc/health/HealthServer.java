package com.informix.grpc.health;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;

public class HealthServer {
    private final HttpServer server;

    private static final Logger logger = LoggerFactory.getLogger(HealthServer.class);

    public HealthServer(int port) throws IOException {
        server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/healthz", exchange -> {
            byte[] response = "OK".getBytes();
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
    }

    public void start() {
        server.start();
        logger.info("Health server on port " + server.getAddress().getPort());
    }

    public void stop() {
        server.stop(0);
    }
}
