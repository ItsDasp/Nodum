package com.nodum.modules.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RemoteProviderConfiguration {
    @Bean
    RemoteModuleAdapter gatewayRemoteAdapter(ObjectMapper objectMapper,
                                             InfrastructureAgentClient infrastructureAgent,
                                             @Value("${nodum.gateway.remote-url:}") String gatewayUrl,
                                             @Value("${nodum.gateway.remote-token:}") String gatewayToken) {
        RemoteModuleClient client = new RemoteModuleClient(objectMapper, gatewayUrl, gatewayToken);
        return new RemoteModuleAdapter(client, infrastructureAgent, objectMapper,
                "http-gateway", "1.0.0", "API Gateway",
                "Enruta solicitudes HTTP a upstreams aprobados.",
                java.util.Set.of("gateway.routes", "gateway.proxy", "gateway.health"));
    }

    @Bean
    RemoteModuleAdapter kafkaRemoteAdapter(ObjectMapper objectMapper,
                                           InfrastructureAgentClient infrastructureAgent,
                                           @Value("${nodum.kafka.remote-url:}") String kafkaUrl,
                                           @Value("${nodum.kafka.remote-token:}") String kafkaToken) {
        RemoteModuleClient client = new RemoteModuleClient(objectMapper, kafkaUrl, kafkaToken);
        return new RemoteModuleAdapter(client, infrastructureAgent, objectMapper,
                "kafka-messaging", "1.0.0", "Kafka",
                "Publica eventos y consume mensajes desde un topic.",
                java.util.Set.of("messaging.produce", "messaging.consume", "messaging.health"));
    }
}
