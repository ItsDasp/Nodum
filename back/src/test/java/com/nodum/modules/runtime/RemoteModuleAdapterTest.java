package com.nodum.modules.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nodum.modules.spi.ModuleHealth;
import com.nodum.modules.spi.ModuleInstanceContext;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RemoteModuleAdapterTest {
    @Test
    void kafkaRemoteAdapterKeepsItsProviderIdentity() {
        RemoteModuleClient client = new RemoteModuleClient(new ObjectMapper(), "", "");
        InfrastructureAgentClient infrastructureAgent = new InfrastructureAgentClient(new ObjectMapper(), "", "");
        RemoteModuleAdapter adapter = new RemoteModuleAdapter(client, infrastructureAgent, new ObjectMapper(),
                "kafka-messaging", "1.0.0", "Kafka", "Publica eventos y consume mensajes.",
                java.util.Set.of("messaging.produce", "messaging.consume", "messaging.health"));

        assertEquals("kafka-messaging", adapter.descriptor().id());
        assertEquals("1.0.0", adapter.descriptor().version());
    }

    @Test
    void unreachableGatewayIsReportedAsDegraded() {
        RemoteModuleClient client = new RemoteModuleClient(
                new ObjectMapper(), "http://127.0.0.1:1", "test-module-token");
        InfrastructureAgentClient infrastructureAgent = new InfrastructureAgentClient(
                new ObjectMapper(), "", "");
        RemoteModuleAdapter adapter = new RemoteModuleAdapter(client, infrastructureAgent, new ObjectMapper(),
                "http-gateway", "1.0.0", "API Gateway",
                "Enruta solicitudes HTTP a upstreams aprobados.",
                java.util.Set.of("gateway.routes", "gateway.proxy", "gateway.health"));

        ModuleHealth health = adapter.health(new ModuleInstanceContext(UUID.randomUUID(), UUID.randomUUID()));

        assertEquals(ModuleHealth.State.DEGRADED, health.state());
    }
}
