package com.nodum.modules.providers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nodum.modules.spi.ModuleDescriptor;
import com.nodum.modules.spi.ModuleHealth;
import com.nodum.modules.spi.ModuleInstanceContext;
import com.nodum.modules.spi.ModulePlugin;
import com.nodum.modules.spi.ModuleValidation;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Component
public class GatewayModule implements ModulePlugin {
    private static final int MAX_BODY_BYTES = 1_048_576;
    private static final Set<String> METHODS = Set.of("GET", "POST", "PUT", "PATCH", "DELETE");
    private static final Set<String> REQUEST_HEADERS = Set.of("accept", "content-type", "if-match", "if-none-match", "x-request-id");
    private final ObjectMapper objectMapper;
    private final Set<String> allowedHosts;
    private final boolean allowPrivateAddresses;
    private final HttpClient httpClient;
    private final ConcurrentMap<UUID, GatewayConfig> instances = new ConcurrentHashMap<>();

    public GatewayModule(ObjectMapper objectMapper,
                         @Value("${nodum.gateway.allowed-hosts:}") String allowedHosts,
                         @Value("${nodum.gateway.allow-private-addresses:false}") boolean allowPrivateAddresses) {
        this.objectMapper = objectMapper;
        this.allowPrivateAddresses = allowPrivateAddresses;
        this.allowedHosts = java.util.Arrays.stream(allowedHosts.split(","))
                .map(String::trim).filter(value -> !value.isBlank()).map(String::toLowerCase).collect(java.util.stream.Collectors.toUnmodifiableSet());
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3))
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }

    @Override
    public ModuleDescriptor descriptor() {
        return new ModuleDescriptor("http-gateway", "1.0.0", 1, "API Gateway",
                "Enruta solicitudes HTTP a upstreams aprobados.", Set.of("gateway.routes", "gateway.proxy", "gateway.health"));
    }

    @Override
    public ModuleValidation validateConfiguration(String json) {
        try {
            JsonNode config = objectMapper.readTree(json);
            if (config == null || !config.isObject()) return ModuleValidation.invalid("La configuración debe ser un objeto JSON.");
            var rootFields = config.fieldNames();
            while (rootFields.hasNext()) if (!Set.of("routes", "public").contains(rootFields.next())) return ModuleValidation.invalid("Propiedad Gateway no admitida.");
            if (config.has("public") && !config.get("public").isBoolean()) return ModuleValidation.invalid("public debe ser true o false.");
            JsonNode routes = config == null ? null : config.get("routes");
            if (routes == null || !routes.isArray() || routes.isEmpty()) return ModuleValidation.invalid("Define al menos una ruta en routes.");
            for (JsonNode route : routes) {
                var routeFields = route.fieldNames();
                while (routeFields.hasNext()) {
                    String key = routeFields.next();
                    if (!Set.of("pathPrefix", "upstreamUrl").contains(key)) return ModuleValidation.invalid("Propiedad Gateway no admitida: " + key + ".");
                }
                if (!route.hasNonNull("pathPrefix") || !route.hasNonNull("upstreamUrl")) return ModuleValidation.invalid("Cada ruta necesita pathPrefix y upstreamUrl.");
                String prefix = route.path("pathPrefix").asText();
                if (!prefix.startsWith("/") || prefix.contains("..") || prefix.contains("\\")) return ModuleValidation.invalid("pathPrefix debe ser una ruta absoluta y segura.");
                URI upstream = URI.create(route.path("upstreamUrl").asText());
                if (!Set.of("http", "https").contains(upstream.getScheme()) || upstream.getHost() == null
                        || upstream.getUserInfo() != null || upstream.getQuery() != null || upstream.getFragment() != null) {
                    return ModuleValidation.invalid("upstreamUrl debe ser una URL HTTP(S) base, sin credenciales ni query.");
                }
                String host = upstream.getHost().toLowerCase();
                if (!allowedHosts.contains(host)) return ModuleValidation.invalid("El host upstream no está en NODUM_GATEWAY_ALLOWED_HOSTS.");
                if (!isSafeUpstreamHost(host)) return ModuleValidation.invalid("El host upstream resuelve a una dirección local o privada.");
            }
            return ModuleValidation.ok();
        } catch (Exception invalid) {
            return ModuleValidation.invalid("La configuración Gateway no es JSON válido.");
        }
    }

    private boolean isSafeUpstreamHost(String host) {
        if (allowPrivateAddresses) return true;
        try {
            for (InetAddress address : InetAddress.getAllByName(host)) {
                if (address.isAnyLocalAddress() || address.isLoopbackAddress()
                        || address.isLinkLocalAddress() || address.isSiteLocalAddress()
                        || address.isMulticastAddress()) return false;
                byte[] bytes = address.getAddress();
                if (bytes.length == 16 && ((bytes[0] & 0xff) == 0xfc || (bytes[0] & 0xff) == 0xfd)) return false;
            }
            return true;
        } catch (java.net.UnknownHostException unresolved) {
            return false;
        }
    }

    @Override
    public void start(ModuleInstanceContext context, String configurationJson) {
        ModuleValidation validation = validateConfiguration(configurationJson);
        if (!validation.valid()) throw new IllegalArgumentException("Invalid Gateway configuration");
        JsonNode parsed;
        try { parsed = objectMapper.readTree(configurationJson); }
        catch (Exception invalid) { throw new IllegalArgumentException("Invalid Gateway configuration"); }
        List<Route> routes = new ArrayList<>();
        parsed.path("routes").forEach(route -> routes.add(new Route(route.path("pathPrefix").asText(), URI.create(route.path("upstreamUrl").asText()))));
        instances.put(context.instanceId(), new GatewayConfig(List.copyOf(routes), parsed.path("public").asBoolean(false), new RateLimiter()));
    }

    @Override
    public void stop(ModuleInstanceContext context) { instances.remove(context.instanceId()); }

    @Override
    public ModuleHealth health(ModuleInstanceContext context) {
        GatewayConfig config = instances.get(context.instanceId());
        return config == null ? new ModuleHealth(ModuleHealth.State.STOPPED, "Gateway detenido.")
                : new ModuleHealth(ModuleHealth.State.RUNNING, config.routes.size() + " ruta(s) listas.");
    }

    public boolean isPublic(UUID instanceId) {
        GatewayConfig config = instances.get(instanceId);
        return config != null && config.publicIngress;
    }

    public boolean isRunning(UUID instanceId) { return instances.containsKey(instanceId); }

    @Override
    public JsonNode execute(ModuleInstanceContext context, String action, JsonNode input) {
        if (!"proxy".equals(action)) throw new UnsupportedOperationException("Unknown Gateway action");
        GatewayConfig config = instances.get(context.instanceId());
        if (config == null) throw new IllegalStateException("Gateway module is not running");
        if (!config.rateLimiter.allow()) throw new GatewayRateLimitException();
        String path = input == null ? "" : input.path("path").asText();
        Route route = config.routes.stream().filter(candidate -> path.equals(candidate.pathPrefix)
                || path.startsWith(candidate.pathPrefix.endsWith("/") ? candidate.pathPrefix : candidate.pathPrefix + "/"))
                .max(java.util.Comparator.comparingInt(candidate -> candidate.pathPrefix.length()))
                .orElseThrow(() -> new IllegalArgumentException("No Gateway route matches this path"));
        return proxy(route, input);
    }

    private JsonNode proxy(Route route, JsonNode input) {
        String method = input.path("method").asText("GET").toUpperCase();
        if (!METHODS.contains(method)) throw new IllegalArgumentException("HTTP method is not supported by this Gateway");
        String path = input.path("path").asText();
        if (path.contains("..") || path.contains("\\") || path.contains("#")) throw new IllegalArgumentException("Unsafe request path");
        String suffix = path.substring(route.pathPrefix.length());
        if (suffix.isBlank()) suffix = "/";
        if (!suffix.startsWith("/")) suffix = "/" + suffix;
        String query = input.hasNonNull("query") ? input.path("query").asText() : null;
        if (query != null && (query.contains("#") || query.contains("\\"))) throw new IllegalArgumentException("Unsafe query string");

        URI target;
        try {
            String basePath = route.upstream.getRawPath() == null ? "" : route.upstream.getRawPath().replaceAll("/$", "");
            String rawPath = basePath.isBlank() ? path : basePath + path;
            String decodedPath = java.net.URLDecoder.decode(rawPath.replace("+", "%2B"), StandardCharsets.UTF_8);
            for (String segment : decodedPath.split("/")) if ("..".equals(segment)) throw new IllegalArgumentException("Invalid route path");
            String rawTarget = route.upstream.getScheme() + "://" + route.upstream.getRawAuthority() + rawPath
                    + (query == null || query.isBlank() ? "" : "?" + query);
            target = URI.create(rawTarget).normalize();
            if (!target.getHost().equalsIgnoreCase(route.upstream.getHost())
                    || !target.getPath().startsWith(basePath + "/") && !target.getPath().equals(basePath)) {
                throw new IllegalArgumentException("Invalid route path");
            }
        } catch (Exception invalid) {
            throw new IllegalArgumentException("Invalid Gateway request path");
        }

        byte[] body = input.hasNonNull("body") ? input.get("body").isTextual()
                ? input.get("body").asText().getBytes(StandardCharsets.UTF_8)
                : input.get("body").toString().getBytes(StandardCharsets.UTF_8) : new byte[0];
        if (body.length > MAX_BODY_BYTES) throw new IllegalArgumentException("Gateway request body exceeds 1 MiB");
        HttpRequest.Builder request = HttpRequest.newBuilder(target).timeout(Duration.ofSeconds(8));
        JsonNode headers = input.path("headers");
        if (headers.isObject()) headers.fields().forEachRemaining(header -> {
            String name = header.getKey().toLowerCase();
            if (REQUEST_HEADERS.contains(name) && header.getValue().isTextual()) request.header(header.getKey(), header.getValue().asText());
        });
        request.method(method, body.length == 0 ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(body));
        try {
            HttpResponse<InputStream> response = httpClient.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
            byte[] responseBody;
            try (InputStream stream = response.body()) { responseBody = stream.readNBytes(MAX_BODY_BYTES + 1); }
            if (responseBody.length > MAX_BODY_BYTES) throw new IllegalStateException("Gateway upstream response exceeds 1 MiB");
            ObjectNode result = objectMapper.createObjectNode();
            result.put("status", response.statusCode());
            result.put("body", new String(responseBody, StandardCharsets.UTF_8));
            result.put("contentType", response.headers().firstValue("content-type").orElse("application/octet-stream"));
            return result;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Gateway request was interrupted");
        } catch (java.io.IOException failure) {
            throw new IllegalStateException("Gateway could not reach the configured upstream");
        }
    }

    private record Route(String pathPrefix, URI upstream) { }
    private record GatewayConfig(List<Route> routes, boolean publicIngress, RateLimiter rateLimiter) { }

    public static class GatewayRateLimitException extends RuntimeException {
        public GatewayRateLimitException() { super("Gateway instance exceeded 120 requests per minute"); }
    }

    private static final class RateLimiter {
        private long windowStart = System.currentTimeMillis();
        private int requests;

        private synchronized boolean allow() {
            long now = System.currentTimeMillis();
            if (now - windowStart >= 60_000) { windowStart = now; requests = 0; }
            return ++requests <= 120;
        }
    }
}
