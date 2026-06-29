package com.informix.grpc;

import static org.assertj.core.api.Assertions.*;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import org.junit.jupiter.api.*;

@Tag("integration")
class InformixRunningStackTest {

    private static ManagedChannel channel;
    private static InformixServiceGrpc.InformixServiceBlockingStub stub;

    private static final String PROXY_HOST = "127.0.0.1";
    private static final int PROXY_PORT = 50051;

    @BeforeAll
    static void setUp() {
        channel = ManagedChannelBuilder.forAddress(PROXY_HOST, PROXY_PORT)
                .usePlaintext()
                .build();
        stub = InformixServiceGrpc.newBlockingStub(channel);
    }

    @AfterAll
    static void tearDown() {
        if (channel != null) {
            channel.shutdown();
        }
    }

    @Test
    void shouldConnectPingQueryAndDisconnect() {
        // Connect (using the default credentials from your docker-compose)
        ConnectionResponse connResp = stub.connect(ConnectionRequest.newBuilder()
                .setHost(PROXY_HOST)
                .setPort(9088)
                .setDatabase("sysmaster")
                .setUsername("informix")
                .setPassword("in4mix")
                .build());
        
        if (!connResp.getSuccess()) {
            System.out.println("Connect error: " + connResp.getError());
        }
        assertThat(connResp.getSuccess()).isTrue(); 
        String connId = connResp.getConnectionId();
        assertThat(connId).isNotEmpty();

        // Ping
        PingResponse pingResp = stub.ping(PingRequest.newBuilder().setConnectionId(connId).build());
        assertThat(pingResp.getAlive()).isTrue();
        assertThat(pingResp.getLatencyMs()).isGreaterThanOrEqualTo(0);

        // Query
        QueryResponse queryResp = stub.executeQuery(QueryRequest.newBuilder()
                .setConnectionId(connId)
                .setSql("SELECT first 1 tabname FROM systables WHERE tabid = 1")
                .build()).next(); // at least one response
        assertThat(queryResp.getColumnsList()).isNotEmpty();
        assertThat(queryResp.getRowsList()).isNotEmpty();

        // 4. Disconnect
        DisconnectResponse disResp = stub.disconnect(DisconnectRequest.newBuilder().setConnectionId(connId).build());
        assertThat(disResp.getSuccess()).isTrue();
    }
}
