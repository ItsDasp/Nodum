package com.nodum.modules.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RemoteModuleClientTest {
    @Test
    void sendsVersionCorrelationAndBearerTokenToRemoteGateway() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/modules/health/11111111-1111-1111-1111-111111111111", exchange -> {
            assertEquals("1", exchange.getRequestHeaders().getFirst("X-Nodum-Contract-Version"));
            assertEquals("Bearer test-module-token", exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] body = """
                    {"contractVersion":"1","correlationId":"22222222-2222-2222-2222-222222222222","instanceId":"11111111-1111-1111-1111-111111111111","success":true,"observedState":"RUNNING","output":{},"error":null,"message":"healthy"}
                    """.getBytes();
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            RemoteModuleClient client = new RemoteModuleClient(new ObjectMapper(),
                    "http://127.0.0.1:" + server.getAddress().getPort(), "test-module-token");
            UUID correlationId = UUID.fromString("22222222-2222-2222-2222-222222222222");
            var response = client.health(UUID.fromString("11111111-1111-1111-1111-111111111111"), correlationId);
            assertTrue(response.success());
            assertEquals("RUNNING", response.observedState());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void unavailableRemoteGatewayIsRetryableAndDoesNotBlockLong() {
        RemoteModuleClient client = new RemoteModuleClient(new ObjectMapper(),
                "http://127.0.0.1:1", "test-module-token");
        var exception = assertThrows(RemoteModuleClient.RemoteModuleException.class,
                () -> client.health(UUID.randomUUID(), UUID.randomUUID()));
        assertTrue(exception.isRetryable());
    }
}
