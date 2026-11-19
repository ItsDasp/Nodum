package com.nodum.modules.runtime;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.UUID;

public final class RemoteModuleContract {
    public static final String VERSION = "1";

    private RemoteModuleContract() {
    }

    public record Request(
            String contractVersion,
            UUID correlationId,
            UUID instanceId,
            UUID projectId,
            String providerId,
            String providerVersion,
            JsonNode configuration,
            String action,
            JsonNode input
    ) {
        public static Request forInstance(UUID projectId, UUID instanceId, String providerId,
                                          String providerVersion, JsonNode configuration) {
            return new Request(VERSION, UUID.randomUUID(), instanceId, projectId,
                    providerId, providerVersion, configuration, null, null);
        }
    }

    public record Response(
            String contractVersion,
            UUID correlationId,
            String instanceId,
            boolean success,
            String observedState,
            String runtimeId,
            JsonNode output,
            RemoteError error,
            String message
    ) {
    }

    public record RemoteError(
            String code,
            boolean retryable,
            String message,
            JsonNode details
    ) {
    }

    public record ValidationResponse(
            String contractVersion,
            UUID correlationId,
            boolean valid,
            List<String> errors
    ) {
    }
}
