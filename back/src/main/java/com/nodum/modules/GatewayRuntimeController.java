package com.nodum.modules;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nodum.modules.providers.GatewayModule;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;
import java.util.Set;
import java.util.UUID;

@RestController
public class GatewayRuntimeController {
    private static final int MAX_BODY_BYTES = 1_048_576;
    private static final Set<String> ALLOWED_METHODS = Set.of("GET", "POST", "PUT", "PATCH", "DELETE");

    private final ModuleRepository moduleRepository;
    private final ModuleLifecycleService lifecycleService;
    private final ObjectMapper objectMapper;

    public GatewayRuntimeController(ModuleRepository moduleRepository, ModuleLifecycleService lifecycleService,
                                    ObjectMapper objectMapper) {
        this.moduleRepository = moduleRepository;
        this.lifecycleService = lifecycleService;
        this.objectMapper = objectMapper;
    }

    @RequestMapping(path = "/api/v1/runtime/gateway/{projectId}/{moduleId}/**", method = {
            RequestMethod.GET, RequestMethod.POST, RequestMethod.PUT, RequestMethod.PATCH, RequestMethod.DELETE
    })
    public ResponseEntity<String> route(@PathVariable UUID projectId, @PathVariable UUID moduleId,
                                        HttpServletRequest request, Authentication authentication) throws IOException {
        Module module = moduleRepository.findWithProjectById(moduleId)
                .filter(candidate -> candidate.getProject().getId().equals(projectId))
                .filter(candidate -> "http-gateway".equals(candidate.getProviderId()))
                .orElseThrow(() -> new GatewayRuntimeException(HttpStatus.NOT_FOUND, "Gateway module not found"));
        if (!"RUNNING".equals(module.getLifecycleState())
                || !"RUNNING".equals(module.getDesiredState())) {
            throw new GatewayRuntimeException(HttpStatus.SERVICE_UNAVAILABLE, "Gateway module is not running");
        }
        boolean authenticated = authentication != null && authentication.isAuthenticated()
                && !"anonymousUser".equals(authentication.getPrincipal());
        boolean publicIngress;
        try {
            publicIngress = objectMapper.readTree(module.getConfigurationJson()).path("public").asBoolean(false);
        } catch (IOException invalidConfiguration) {
            publicIngress = false;
        }
        if (!publicIngress && !authenticated) {
            throw new GatewayRuntimeException(HttpStatus.UNAUTHORIZED, "This Gateway route requires a panel token");
        }

        String method = request.getMethod();
        if (!ALLOWED_METHODS.contains(method)) throw new GatewayRuntimeException(HttpStatus.METHOD_NOT_ALLOWED, "Method not supported");
        long contentLength = request.getContentLengthLong();
        if (contentLength > MAX_BODY_BYTES) throw new GatewayRuntimeException(HttpStatus.PAYLOAD_TOO_LARGE, "Request body exceeds 1 MiB");
        byte[] rawBody = request.getInputStream().readNBytes(MAX_BODY_BYTES + 1);
        if (rawBody.length > MAX_BODY_BYTES) throw new GatewayRuntimeException(HttpStatus.PAYLOAD_TOO_LARGE, "Request body exceeds 1 MiB");

        String prefix = "/api/v1/runtime/gateway/" + projectId + "/" + moduleId;
        String requestPath = request.getRequestURI().substring(prefix.length());
        var action = objectMapper.createObjectNode().put("method", method).put("path", requestPath);
        if (request.getQueryString() != null) action.put("query", request.getQueryString());
        if (rawBody.length > 0) action.put("body", new String(rawBody, StandardCharsets.UTF_8));
        var headers = action.putObject("headers");
        Enumeration<String> names = request.getHeaderNames();
        while (names.hasMoreElements()) {
            String name = names.nextElement();
            if (name.equalsIgnoreCase("Accept") || name.equalsIgnoreCase("Content-Type")
                    || name.equalsIgnoreCase("If-Match") || name.equalsIgnoreCase("If-None-Match")
                    || name.equalsIgnoreCase("X-Request-Id")) headers.put(name, request.getHeader(name));
        }

        JsonNode response = lifecycleService.execute(module, "proxy", action);
        HttpHeaders responseHeaders = new HttpHeaders();
        try { responseHeaders.setContentType(MediaType.parseMediaType(response.path("contentType").asText("application/octet-stream"))); }
        catch (IllegalArgumentException invalidType) { responseHeaders.setContentType(MediaType.APPLICATION_OCTET_STREAM); }
        int status = response.path("status").asInt(502);
        if (status < 100 || status > 599) status = 502;
        return ResponseEntity.status(status).headers(responseHeaders).body(response.path("body").asText());
    }

    public static class GatewayRuntimeException extends RuntimeException {
        private final HttpStatus status;
        public GatewayRuntimeException(HttpStatus status, String message) { super(message); this.status = status; }
        public HttpStatus getStatus() { return status; }
    }
}
