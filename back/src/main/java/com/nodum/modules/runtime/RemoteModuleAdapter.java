package com.nodum.modules.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nodum.modules.spi.ModuleDescriptor;
import com.nodum.modules.spi.ModuleHealth;
import com.nodum.modules.spi.ModuleInstanceContext;
import com.nodum.modules.spi.ModulePlugin;
import com.nodum.modules.spi.ModuleValidation;

import java.util.Set;
import java.util.UUID;

public class RemoteModuleAdapter implements ModulePlugin {
    private final RemoteModuleClient client;
    private final InfrastructureAgentClient infrastructureAgent;
    private final ObjectMapper objectMapper;
    private final String providerId;
    private final String providerVersion;
    private final String providerName;
    private final String providerDescription;
    private final Set<String> providerCapabilities;

    public RemoteModuleAdapter(RemoteModuleClient client, InfrastructureAgentClient infrastructureAgent,
                               ObjectMapper objectMapper, String providerId, String providerVersion,
                               String providerName, String providerDescription, Set<String> providerCapabilities) {
        this.client = client;
        this.infrastructureAgent = infrastructureAgent;
        this.objectMapper = objectMapper;
        this.providerId = providerId;
        this.providerVersion = providerVersion;
        this.providerName = providerName;
        this.providerDescription = providerDescription;
        this.providerCapabilities = providerCapabilities;
    }

    @Override
    public ModuleDescriptor descriptor() {
        return new ModuleDescriptor(providerId, providerVersion, 1, providerName,
                providerDescription, providerCapabilities);
    }

    public boolean enabled() {
        return client.enabled();
    }

    @Override
    public ModuleValidation validateConfiguration(String configurationJson) {
        if (!enabled()) {
            return ModuleValidation.invalid("Remote Gateway runtime is not configured.");
        }
        try {
            JsonNode configuration = objectMapper.readTree(configurationJson);
            RemoteModuleContract.Request request = new RemoteModuleContract.Request(
                    RemoteModuleContract.VERSION,
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    providerId,
                    providerVersion,
                    configuration,
                    null,
                    null);
            RemoteModuleContract.ValidationResponse response = client.validate(request);
            return response.valid() ? ModuleValidation.ok() :
                    ModuleValidation.invalid(response.errors().isEmpty()
                            ? "Remote Gateway rejected the configuration." : String.join("; ", response.errors()));
        } catch (RemoteModuleClient.RemoteModuleException failure) {
            return ModuleValidation.invalid("Remote Gateway is unavailable.");
        } catch (Exception failure) {
            return ModuleValidation.invalid("Remote Gateway configuration could not be validated.");
        }
    }

    @Override
    public void start(ModuleInstanceContext context, String configurationJson) {
        requireEnabled();
        try {
            JsonNode configuration = objectMapper.readTree(configurationJson);
            if (infrastructureAgent.enabled()) {
                InfrastructureAgentClient.AgentResponse container = infrastructureAgent.start(providerId, context.instanceId(), context.projectId());
                if (!container.success()) throw new InfrastructureAgentClient.InfrastructureAgentException(
                        container.message(), true);
            }
            RemoteModuleContract.Request request = RemoteModuleContract.Request.forInstance(
                    context.projectId(), context.instanceId(), providerId, providerVersion, configuration);
            RemoteModuleContract.Response response = client.start(request);
            if (!response.success()) {
                throw new RemoteModuleClient.RemoteModuleException(
                        response.error() == null ? "Remote Gateway could not start" : response.error().message(),
                        response.error() != null && response.error().retryable());
            }
        } catch (RemoteModuleClient.RemoteModuleException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new RemoteModuleClient.RemoteModuleException("Remote Gateway could not start", true);
        }
    }

    @Override
    public void stop(ModuleInstanceContext context) {
        requireEnabled();
        if (infrastructureAgent.enabled()) {
            InfrastructureAgentClient.AgentResponse container = infrastructureAgent.stop(providerId, context.instanceId());
            if (!container.success()) throw new InfrastructureAgentClient.InfrastructureAgentException(container.message(), true);
        }
        RemoteModuleContract.Request request = RemoteModuleContract.Request.forInstance(
                context.projectId(), context.instanceId(), providerId, providerVersion, null);
        RemoteModuleContract.Response response = client.stop(request);
        if (!response.success()) {
            throw new RemoteModuleClient.RemoteModuleException(
                    response.error() == null ? "Remote Gateway could not stop" : response.error().message(),
                    response.error() != null && response.error().retryable());
        }
    }

    @Override
    public ModuleHealth health(ModuleInstanceContext context) {
        if (!enabled()) return new ModuleHealth(ModuleHealth.State.UNKNOWN, "Remote Gateway is not configured.");
        try {
            if (infrastructureAgent.enabled()) {
                InfrastructureAgentClient.AgentResponse container = infrastructureAgent.inspect(providerId, context.instanceId());
                if (!container.success()) return new ModuleHealth(ModuleHealth.State.DEGRADED,
                        container.message());
            }
            RemoteModuleContract.Response response = client.health(context.instanceId(), UUID.randomUUID());
            return new ModuleHealth(parseState(response.observedState()), safeMessage(response.message()));
        } catch (RemoteModuleClient.RemoteModuleException failure) {
            return new ModuleHealth(ModuleHealth.State.DEGRADED,
                    "Remote Gateway is unreachable.");
        }
    }

    @Override
    public JsonNode execute(ModuleInstanceContext context, String action, JsonNode input) {
        requireEnabled();
        RemoteModuleContract.Request request = new RemoteModuleContract.Request(
                RemoteModuleContract.VERSION,
                UUID.randomUUID(),
                context.instanceId(),
                context.projectId(),
                providerId,
                providerVersion,
                null,
                action,
                input);
        RemoteModuleContract.Response response = client.execute(request);
        if (!response.success()) {
            throw new IllegalStateException(response.error() == null
                    ? "Remote Gateway action failed" : response.error().message());
        }
        return response.output();
    }

    private void requireEnabled() {
        if (!enabled()) throw new RemoteModuleClient.RemoteModuleException("Remote Gateway is not configured", false);
    }

    private ModuleHealth.State parseState(String value) {
        if (value == null) return ModuleHealth.State.UNKNOWN;
        try {
            return ModuleHealth.State.valueOf(value.toUpperCase());
        } catch (IllegalArgumentException failure) {
            return ModuleHealth.State.UNKNOWN;
        }
    }

    private String safeMessage(String message) {
        return message == null || message.isBlank() ? "Remote Gateway health state is unavailable." : message;
    }
}
