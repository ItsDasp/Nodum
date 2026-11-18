package com.nodum.modules.providers;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GatewayModuleTest {
    @Test
    void rejectsLoopbackUpstreamEvenWhenHostIsAllowlisted() {
        GatewayModule gateway = new GatewayModule(new ObjectMapper(), "127.0.0.1", false);

        var validation = gateway.validateConfiguration("""
                {"routes":[{"pathPrefix":"/internal","upstreamUrl":"http://127.0.0.1:8080"}]}
                """);

        assertFalse(validation.valid());
    }

    @Test
    void allowsPrivateUpstreamOnlyWhenExplicitlyConfigured() {
        GatewayModule gateway = new GatewayModule(new ObjectMapper(), "127.0.0.1", true);

        var validation = gateway.validateConfiguration("""
                {"routes":[{"pathPrefix":"/internal","upstreamUrl":"http://127.0.0.1:8080"}]}
                """);

        assertTrue(validation.valid());
    }
}