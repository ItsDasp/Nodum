package com.nodum.modules.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;

@Component
public class InfrastructureAgentClient {
    private final URI baseUrl;
    private final String token;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public InfrastructureAgentClient(ObjectMapper objectMapper,
                                     @Value("${nodum.infrastructure.agent-url:}") String agentUrl,
                                     @Value("${nodum.infrastructure.agent-token:}") String token) {
        this.objectMapper = objectMapper;
        this.token = token;
        this.baseUrl = agentUrl == null || agentUrl.isBlank() ? null : URI.create(agentUrl.replaceAll("/$", ""));
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    }

    public boolean enabled() {
        return baseUrl != null && token != null && !token.isBlank();
    }

    public AgentResponse create(String providerId, UUID instanceId) {
        return operation("create", providerId, instanceId);
    }

    public AgentResponse start(String providerId, UUID instanceId) {
        return operation("start", providerId, instanceId);
    }

    public AgentResponse stop(String providerId, UUID instanceId) {
        return operation("stop", providerId, instanceId);
    }

    public AgentResponse restart(String providerId, UUID instanceId) {
        return operation("restart", providerId, instanceId);
    }

    public AgentResponse inspect(String providerId, UUID instanceId) {
        return operation("inspect", providerId, instanceId);
    }

    private AgentResponse operation(String operation, String providerId, UUID instanceId) {
        if (!enabled()) throw new InfrastructureAgentException("Infrastructure Agent is not configured", false);
        try {
            ObjectNode body = objectMapper.createObjectNode();
            body.put("providerId", providerId);
            body.put("instanceId", instanceId.toString());
            HttpRequest request = HttpRequest.newBuilder(baseUrl.resolve("/v1/operations/" + operation))
                    .header("Authorization", "Bearer " + token)
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(10))
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                    .build();
            HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.body().length > 1_048_576) throw new InfrastructureAgentException("Agent response exceeds 1 MiB", false);
            if (response.statusCode() >= 300) throw new InfrastructureAgentException("Infrastructure Agent returned HTTP " + response.statusCode(), true);
            JsonNode json = objectMapper.readTree(response.body());
            return new AgentResponse(json.path("success").asBoolean(false),
                    json.path("observedState").asText("UNKNOWN"),
                    json.path("runtimeId").isTextual() ? json.path("runtimeId").asText() : null,
                    json.path("message").asText("Infrastructure operation completed"));
        } catch (InfrastructureAgentException failure) {
            throw failure;
        } catch (java.net.http.HttpTimeoutException failure) {
            throw new InfrastructureAgentException("Infrastructure Agent timed out", true);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new InfrastructureAgentException("Infrastructure Agent request was interrupted", true);
        } catch (Exception failure) {
            throw new InfrastructureAgentException("Infrastructure Agent is unreachable", true);
        }
    }

    public record AgentResponse(boolean success, String observedState, String runtimeId, String message) {}

    public static class InfrastructureAgentException extends RuntimeException {
        private final boolean retryable;
        public InfrastructureAgentException(String message, boolean retryable) { super(message); this.retryable = retryable; }
        public boolean isRetryable() { return retryable; }
    }
}
