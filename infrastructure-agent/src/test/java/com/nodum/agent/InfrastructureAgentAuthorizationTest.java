package com.nodum.agent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InfrastructureAgentAuthorizationTest {
    @Test
    void onlyAllowsManagedProvidersAndUuidInstanceIds() {
        assertTrue(InfrastructureAgentController.isAuthorizedRequest(
                "http-gateway", "11111111-1111-1111-1111-111111111111"));
        assertTrue(InfrastructureAgentController.isAuthorizedRequest(
                "kafka-messaging", "11111111-1111-1111-1111-111111111111"));
        assertTrue(InfrastructureAgentController.isAuthorizedRequest(
                "linux-shell", "11111111-1111-1111-1111-111111111111"));
        assertFalse(InfrastructureAgentController.isAuthorizedRequest(
                "rabbit-messaging", "11111111-1111-1111-1111-111111111111"));
        assertFalse(InfrastructureAgentController.isAuthorizedRequest(
                "http-gateway", "arbitrary-command"));
    }
}
