package com.nodum.modules.providers;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KafkaMessagingModuleTest {
    private final KafkaMessagingModule module = new KafkaMessagingModule(new ObjectMapper());

    @Test
    void validatesPartitionsAndReplication() {
        var valid = module.validateConfiguration("""
                {"bootstrapServers":"kafka:9092","topic":"events","groupId":"nodum-events",
                 "consume":true,"partitions":3,"replicationFactor":1,"partition":2}
                """);
        assertTrue(valid.valid());

        var invalid = module.validateConfiguration("""
                {"bootstrapServers":"kafka:9092","topic":"events","groupId":"nodum-events",
                 "consume":true,"partitions":0,"replicationFactor":0,"partition":-1}
                """);
        assertFalse(invalid.valid());
    }

    @Test
    void rejectsUnsupportedPropertiesAndCredentials() {
        var result = module.validateConfiguration("""
                {"bootstrapServers":"kafka:9092","topic":"events","groupId":"nodum-events",
                 "consume":false,"password":"secret"}
                """);
        assertFalse(result.valid());
    }

    @Test
    void rejectsUnknownActionsWhenNotStarted() {
        var context = new com.nodum.modules.spi.ModuleInstanceContext(
                java.util.UUID.randomUUID(), java.util.UUID.randomUUID());
        assertThrows(IllegalStateException.class,
                () -> module.execute(context, "read", null));
    }
}
