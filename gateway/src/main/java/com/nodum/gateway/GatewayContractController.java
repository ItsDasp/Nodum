package com.nodum.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.net.URI;
import java.net.InetAddress;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@RestController
@RequestMapping("/v1/modules")
public class GatewayContractController {
    private static final String CONTRACT_VERSION = "1";
    private static final int MAX_BODY_BYTES = 1_048_576;

    private final ObjectMapper objectMapper;
    private final Set<String> allowedHosts;
    private final boolean allowPrivateAddresses;
    private final HttpClient httpClient;
    private final Map<String, GatewayInstance> instances = new ConcurrentHashMap<>();

    public GatewayContractController(ObjectMapper objectMapper,
                                     @Value("${nodum.gateway.allowed-hosts:}") String allowedHosts,
                                     @Value("${nodum.gateway.allow-private-addresses:false}") boolean allowPrivateAddresses) {
        this.objectMapper = objectMapper;
        this.allowPrivateAddresses = allowPrivateAddresses;
        this.allowedHosts = Arrays.stream(allowedHosts.split(",")).map(String::trim).filter(v -> !v.isBlank()).map(String::toLowerCase).collect(java.util.stream.Collectors.toUnmodifiableSet());
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).followRedirects(HttpClient.Redirect.NEVER).build();
    }

    @PostMapping("/validate")
    public ResponseEntity<JsonNode> validate(@RequestHeader("X-Nodum-Contract-Version") String contractVersion,
                                             @RequestBody JsonNode request) {
        if (!CONTRACT_VERSION.equals(contractVersion)) return response(request, false, "UNKNOWN", null,
                "UNSUPPORTED_CONTRACT", false, "Unsupported contract version");
        ObjectNode result = objectMapper.createObjectNode();
        result.put("contractVersion", CONTRACT_VERSION);
        result.put("correlationId", request.path("correlationId").asText());
        List<String> errors = validateConfiguration(request.path("configuration"));
        result.put("valid", errors.isEmpty());
        var array = result.putArray("errors");
        errors.forEach(array::add);
        return ResponseEntity.ok(result);
    }

    @PostMapping("/start")
    public ResponseEntity<JsonNode> start(@RequestHeader("X-Nodum-Contract-Version") String contractVersion,
                                          @RequestBody JsonNode request) {
        if (!CONTRACT_VERSION.equals(contractVersion)) return response(request, false, "UNKNOWN", null,
                "UNSUPPORTED_CONTRACT", false, "Unsupported contract version");
        String instanceId = request.path("instanceId").asText();
        List<String> errors = validateConfiguration(request.path("configuration"));
        if (!instanceId.matches("[0-9a-fA-F-]{36}") || !errors.isEmpty()) {
            return response(request, false, "ERROR", null, "INVALID_CONFIGURATION", false,
                    String.join("; ", errors.isEmpty() ? List.of("Invalid instance ID") : errors));
        }
        instances.put(instanceId, new GatewayInstance(request.path("configuration")));
        return response(request, true, "RUNNING", instanceId, null, false, "Gateway started");
    }

    @PostMapping("/stop")
    public ResponseEntity<JsonNode> stop(@RequestHeader("X-Nodum-Contract-Version") String contractVersion,
                                         @RequestBody JsonNode request) {
        if (!CONTRACT_VERSION.equals(contractVersion)) return response(request, false, "UNKNOWN", null,
                "UNSUPPORTED_CONTRACT", false, "Unsupported contract version");
        String instanceId = request.path("instanceId").asText();
        instances.remove(instanceId);
        return response(request, true, "STOPPED", instanceId, null, false, "Gateway stopped");
    }

    @GetMapping("/health/{instanceId}")
    public ResponseEntity<JsonNode> health(@RequestHeader("X-Nodum-Contract-Version") String contractVersion,
                                           @PathVariable String instanceId,
                                           @RequestHeader(value = "X-Nodum-Correlation-ID", required = false) String correlationId) {
        if (!CONTRACT_VERSION.equals(contractVersion)) return response(instanceId, correlationId, false, "UNKNOWN",
                "UNSUPPORTED_CONTRACT", false, "Unsupported contract version");
        if (!instances.containsKey(instanceId)) return response(instanceId, correlationId, true, "STOPPED",
                null, false, "Gateway instance is stopped");
        return response(instanceId, correlationId, true, "RUNNING", null, false, "Gateway instance is healthy");
    }

    @PostMapping("/execute/{instanceId}/{action}")
    public ResponseEntity<JsonNode> execute(@RequestHeader("X-Nodum-Contract-Version") String contractVersion,
                                            @PathVariable String instanceId,
                                            @PathVariable String action,
                                            @RequestBody JsonNode request) {
        if (!CONTRACT_VERSION.equals(contractVersion)) return response(request, false, "UNKNOWN", null,
                "UNSUPPORTED_CONTRACT", false, "Unsupported contract version");
        GatewayInstance instance = instances.get(instanceId);
        if (instance == null) return response(request, false, "STOPPED", instanceId,
                "INSTANCE_NOT_RUNNING", false, "Gateway instance is not running");
        if (!"proxy".equals(action)) return response(request, false, "RUNNING", instanceId,
                "UNKNOWN_ACTION", false, "Unknown Gateway action");
        try {
            JsonNode output = proxy(instance, request.path("input"));
            return response(request, true, "RUNNING", instanceId, null, false, "Gateway action completed", output);
        } catch (IllegalArgumentException failure) {
            return response(request, false, "RUNNING", instanceId, "INVALID_REQUEST", false, failure.getMessage());
        } catch (IOException failure) {
            return response(request, false, "DEGRADED", instanceId, "UPSTREAM_UNAVAILABLE", true,
                    "Gateway could not reach the configured upstream");
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            return response(request, false, "DEGRADED", instanceId, "REQUEST_INTERRUPTED", true,
                    "Gateway request was interrupted");
        }
    }

    private List<String> validateConfiguration(JsonNode config) {
        List<String> errors = new ArrayList<>();
        if (config == null || !config.isObject()) return List.of("Configuration must be a JSON object.");
        JsonNode routes = config.get("routes");
        if (routes == null || !routes.isArray() || routes.isEmpty()) return List.of("At least one route is required.");
        for (JsonNode route : routes) {
            try {
                URI uri = URI.create(route.path("upstreamUrl").asText());
                if (!Set.of("http", "https").contains(uri.getScheme()) || uri.getHost() == null
                        || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null) {
                    errors.add("upstreamUrl must be an HTTP(S) URL without credentials or query.");
                    continue;
                }
                String host = uri.getHost().toLowerCase();
                if (!allowedHosts.contains(host)) errors.add("Upstream host is not allowed.");
                if (!allowPrivateAddresses && !isSafeHost(host)) errors.add("Upstream host resolves to a private address.");
            } catch (Exception failure) {
                errors.add("Invalid upstream URL.");
            }
        }
        return errors;
    }

    private boolean isSafeHost(String host) {
        try {
            for (InetAddress address : InetAddress.getAllByName(host)) {
                if (address.isAnyLocalAddress() || address.isLoopbackAddress()
                        || address.isLinkLocalAddress() || address.isSiteLocalAddress()
                        || address.isMulticastAddress()) return false;
            }
            return true;
        } catch (Exception failure) {
            return false;
        }
    }

    private JsonNode proxy(GatewayInstance instance, JsonNode input) throws IOException, InterruptedException {
        String method = input.path("method").asText("GET").toUpperCase();
        if (!Set.of("GET", "POST", "PUT", "PATCH", "DELETE").contains(method)) throw new IllegalArgumentException("HTTP method is not supported.");
        String path = input.path("path").asText();
        if (path.contains("..") || path.contains("\\") || path.contains("#")) throw new IllegalArgumentException("Unsafe request path.");
        var route = instance.routes.stream()
                .filter(candidate -> path.equals(candidate.pathPrefix()) || path.startsWith(candidate.pathPrefix().endsWith("/") ? candidate.pathPrefix() : candidate.pathPrefix() + "/"))
                .max(Comparator.comparingInt(candidate -> candidate.pathPrefix().length()))
                .orElseThrow(() -> new IllegalArgumentException("No Gateway route matches this path."));
        String suffix = path.substring(route.pathPrefix().length());
        if (suffix.isBlank()) suffix = "/";
        if (!suffix.startsWith("/")) suffix = "/" + suffix;
        URI target = URI.create(route.upstream().toString().replaceAll("/$", "") + suffix);
        HttpRequest.Builder builder = HttpRequest.newBuilder(target).timeout(Duration.ofSeconds(8));
        byte[] body = input.hasNonNull("body") ? input.get("body").toString().getBytes(StandardCharsets.UTF_8) : new byte[0];
        if (body.length > MAX_BODY_BYTES) throw new IllegalArgumentException("Request body exceeds 1 MiB.");
        builder.method(method, body.length == 0 ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(body));
        HttpResponse<byte[]> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
        if (response.body().length > MAX_BODY_BYTES) throw new IllegalStateException("Upstream response exceeds 1 MiB.");
        ObjectNode output = objectMapper.createObjectNode();
        output.put("status", response.statusCode());
        output.put("body", new String(response.body(), StandardCharsets.UTF_8));
        output.put("contentType", response.headers().firstValue("content-type").orElse("application/octet-stream"));
        return output;
    }

    private ResponseEntity<JsonNode> response(JsonNode request, boolean success, String observedState,
                                               String runtimeId, String code, boolean retryable, String message) {
        return response(request, success, observedState, runtimeId, code, retryable, message, null);
    }

    private ResponseEntity<JsonNode> response(JsonNode request, boolean success, String observedState,
                                               String runtimeId, String code, boolean retryable, String message,
                                               JsonNode output) {
        ObjectNode result = objectMapper.createObjectNode();
        result.put("contractVersion", CONTRACT_VERSION);
        result.put("correlationId", request.path("correlationId").asText(UUID.randomUUID().toString()));
        result.put("instanceId", request.path("instanceId").asText());
        result.put("success", success);
        result.put("observedState", observedState);
        if (runtimeId != null) result.put("runtimeId", runtimeId);
        if (output != null) result.set("output", output);
        else result.putNull("output");
        if (code != null) {
            ObjectNode error = result.putObject("error");
            error.put("code", code);
            error.put("retryable", retryable);
            error.put("message", message);
            error.putNull("details");
        } else {
            result.putNull("error");
        }
        result.put("message", message);
        return success ? ResponseEntity.ok(result) : ResponseEntity.status(422).body(result);
    }

    private ResponseEntity<JsonNode> response(String instanceId, String correlationId, boolean success,
                                               String observedState, String code, boolean retryable, String message) {
        ObjectNode result = objectMapper.createObjectNode();
        result.put("contractVersion", CONTRACT_VERSION);
        result.put("correlationId", correlationId == null ? UUID.randomUUID().toString() : correlationId);
        result.put("instanceId", instanceId);
        result.put("success", success);
        result.put("observedState", observedState);
        result.put("runtimeId", instanceId);
        result.putNull("output");
        if (code == null) result.putNull("error");
        else {
            ObjectNode error = result.putObject("error");
            error.put("code", code);
            error.put("retryable", retryable);
            error.put("message", message);
            error.putNull("details");
        }
        result.put("message", message);
        return success ? ResponseEntity.ok(result) : ResponseEntity.status(422).body(result);
    }

    private record Route(String pathPrefix, URI upstream) {}

    private static final class GatewayInstance {
        private final List<Route> routes;

        private GatewayInstance(JsonNode configuration) {
            List<Route> parsedRoutes = new ArrayList<>();
            configuration.path("routes").forEach(route -> parsedRoutes.add(new Route(
                    route.path("pathPrefix").asText(),
                    URI.create(route.path("upstreamUrl").asText()))));
            this.routes = List.copyOf(parsedRoutes);
        }
    }
}
