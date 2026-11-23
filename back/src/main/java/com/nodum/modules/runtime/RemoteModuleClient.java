package com.nodum.modules.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;

@Component
public class RemoteModuleClient {
    private static final int MAX_RESPONSE_BYTES = 1_048_576;

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final URI baseUrl;
    private final String serviceToken;

    public RemoteModuleClient(ObjectMapper objectMapper,
                              @Value("${nodum.gateway.remote-url:}") String remoteUrl,
                              @Value("${nodum.gateway.remote-token:}") String serviceToken) {
        this.objectMapper = objectMapper;
        this.serviceToken = serviceToken;
        this.baseUrl = remoteUrl == null || remoteUrl.isBlank() ? null : URI.create(remoteUrl.replaceAll("/$", ""));
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .build();
    }

    public boolean enabled() {
        return baseUrl != null;
    }

    public RemoteModuleContract.ValidationResponse validate(RemoteModuleContract.Request request) {
        return post("/v1/modules/validate", request, RemoteModuleContract.ValidationResponse.class);
    }

    public RemoteModuleContract.Response start(RemoteModuleContract.Request request) {
        return post("/v1/modules/start", request, RemoteModuleContract.Response.class);
    }

    public RemoteModuleContract.Response stop(RemoteModuleContract.Request request) {
        return post("/v1/modules/stop", request, RemoteModuleContract.Response.class);
    }

    public RemoteModuleContract.Response health(UUID instanceId, UUID correlationId) {
        return get("/v1/modules/health/" + instanceId, correlationId, RemoteModuleContract.Response.class);
    }

    public RemoteModuleContract.Response execute(RemoteModuleContract.Request request) {
        return post("/v1/modules/execute/" + request.instanceId() + "/" + request.action(),
                request, RemoteModuleContract.Response.class);
    }

    private <T> T post(String path, Object body, Class<T> type) {
        try {
            String json = objectMapper.writeValueAsString(body);
            HttpRequest request = authenticatedRequest(path)
                    .timeout(Duration.ofSeconds(8))
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .header("Content-Type", "application/json")
                    .build();
            return send(request, type);
        } catch (RemoteModuleException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new RemoteModuleException("Remote module request failed", true);
        }
    }

    private <T> T get(String path, UUID correlationId, Class<T> type) {
        try {
            HttpRequest request = authenticatedRequest(path)
                    .timeout(Duration.ofSeconds(5))
                    .header("X-Nodum-Correlation-ID", correlationId.toString())
                    .GET()
                    .build();
            return send(request, type);
        } catch (RemoteModuleException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new RemoteModuleException("Remote module health request failed", true);
        }
    }

    private HttpRequest.Builder authenticatedRequest(String path) {
        if (!enabled()) throw new RemoteModuleException("Remote module URL is not configured", false);
        HttpRequest.Builder builder = HttpRequest.newBuilder(baseUrl.resolve(path))
                .header("X-Nodum-Contract-Version", RemoteModuleContract.VERSION);
        if (serviceToken != null && !serviceToken.isBlank()) {
            builder.header("Authorization", "Bearer " + serviceToken);
        }
        return builder;
    }

    private <T> T send(HttpRequest request, Class<T> type) throws Exception {
        HttpResponse<byte[]> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
        } catch (java.net.http.HttpTimeoutException failure) {
            throw new RemoteModuleException("Remote module request timed out", true);
        } catch (java.io.IOException failure) {
            throw new RemoteModuleException("Remote module is unreachable", true);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new RemoteModuleException("Remote module request was interrupted", true);
        }

        byte[] body = response.body();
        if (body.length > MAX_RESPONSE_BYTES) {
            throw new RemoteModuleException("Remote module response exceeds 1 MiB", false);
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new RemoteModuleException("Remote module returned HTTP " + response.statusCode(),
                    response.statusCode() >= 500);
        }
        JsonNode json = objectMapper.readTree(body);
        if (json == null || !RemoteModuleContract.VERSION.equals(json.path("contractVersion").asText())) {
            throw new RemoteModuleException("Unsupported remote module contract version", false);
        }
        return objectMapper.treeToValue(json, type);
    }

    public static class RemoteModuleException extends RuntimeException {
        private final boolean retryable;

        public RemoteModuleException(String message, boolean retryable) {
            super(message);
            this.retryable = retryable;
        }

        public boolean isRetryable() {
            return retryable;
        }
    }
}
