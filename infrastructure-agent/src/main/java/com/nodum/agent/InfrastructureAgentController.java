package com.nodum.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.function.Supplier;

@RestController
@RequestMapping("/v1/operations")
public class InfrastructureAgentController {
    private final DockerModuleAgentService service;
    private final ObjectMapper objectMapper;

    public InfrastructureAgentController(DockerModuleAgentService service, ObjectMapper objectMapper) {
        this.service = service;
        this.objectMapper = objectMapper;
    }

    @PostMapping("/create")
    public ResponseEntity<JsonNode> create(@RequestBody JsonNode request) {
        String providerId = request.path("providerId").asText();
        String instanceId = request.path("instanceId").asText();
        String projectId = request.path("projectId").isTextual() ? request.path("projectId").asText() : null;
        if (!isAuthorizedRequest(providerId, instanceId)) {
            return rejectedProviderOrInstance();
        }
        return operate(() -> service.create(providerId, instanceId, projectId));
    }

    @PostMapping("/start")
    public ResponseEntity<JsonNode> start(@RequestBody JsonNode request) {
        String providerId = request.path("providerId").asText();
        String instanceId = request.path("instanceId").asText();
        String projectId = request.path("projectId").isTextual() ? request.path("projectId").asText() : null;
        if (!isAuthorizedRequest(providerId, instanceId)) return rejectedProviderOrInstance();
        return operate(() -> service.start(providerId, instanceId, projectId));
    }

    @PostMapping("/stop")
    public ResponseEntity<JsonNode> stop(@RequestBody JsonNode request) {
        String providerId = request.path("providerId").asText();
        String instanceId = request.path("instanceId").asText();
        if (!isAuthorizedRequest(providerId, instanceId)) return rejectedProviderOrInstance();
        return operate(() -> service.stop(providerId, instanceId));
    }

    @PostMapping("/restart")
    public ResponseEntity<JsonNode> restart(@RequestBody JsonNode request) {
        String providerId = request.path("providerId").asText();
        String instanceId = request.path("instanceId").asText();
        if (!isAuthorizedRequest(providerId, instanceId)) return rejectedProviderOrInstance();
        return operate(() -> service.restart(providerId, instanceId));
    }

    @PostMapping("/inspect")
    public ResponseEntity<JsonNode> inspect(@RequestBody JsonNode request) {
        String providerId = request.path("providerId").asText();
        String instanceId = request.path("instanceId").asText();
        if (!isAuthorizedRequest(providerId, instanceId)) return rejectedProviderOrInstance();
        return operate(() -> service.inspect(providerId, instanceId));
    }

    @GetMapping("/runtime/{providerId}/{instanceId}/logs")
    public ResponseEntity<JsonNode> logs(@PathVariable String providerId, @PathVariable String instanceId,
                                         @RequestParam(defaultValue = "200") int tail) {
        if (!isAuthorizedRequest(providerId, instanceId)) return rejectedProviderOrInstance();
        return runtime(() -> service.logs(providerId, instanceId, tail));
    }

    @PostMapping("/runtime/{providerId}/{instanceId}/shell")
    public ResponseEntity<JsonNode> shell(@PathVariable String providerId, @PathVariable String instanceId,
                                          @RequestBody JsonNode request) {
        if (!isAuthorizedRequest(providerId, instanceId)) return rejectedProviderOrInstance();
        return runtime(() -> service.execute(providerId, instanceId, request.path("command").asText(),
                request.path("workingDirectory").asText("/app")));
    }

    private ResponseEntity<JsonNode> runtime(Supplier<DockerModuleAgentService.RuntimeCommandResult> operation) {
        try {
            var result = operation.get();
            ObjectNode response = objectMapper.createObjectNode();
            response.put("output", result.output());
            if (result.exitCode() == null) response.putNull("exitCode");
            else response.put("exitCode", result.exitCode());
            response.put("timedOut", result.timedOut());
            response.put("truncated", result.truncated());
            if (result.workingDirectory() == null) response.putNull("workingDirectory");
            else response.put("workingDirectory", result.workingDirectory());
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException failure) {
            return ResponseEntity.badRequest().body(objectMapper.createObjectNode().put("message", failure.getMessage()));
        } catch (IllegalStateException failure) {
            return ResponseEntity.status(409).body(objectMapper.createObjectNode().put("message", failure.getMessage()));
        } catch (Exception failure) {
            return ResponseEntity.status(503).body(objectMapper.createObjectNode().put("message", "No se pudo consultar el contenedor del módulo."));
        }
    }

    private ResponseEntity<JsonNode> operate(AgentOperation operation) {
        try {
            DockerModuleAgentService.AgentOperationResult result = operation.run();
            ObjectNode response = objectMapper.createObjectNode();
            response.put("success", result.success());
            response.put("observedState", result.observedState());
            if (result.runtimeId() != null) response.put("runtimeId", result.runtimeId());
            else response.putNull("runtimeId");
            response.put("message", result.message());
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException failure) {
            ObjectNode response = objectMapper.createObjectNode();
            response.put("success", false);
            response.put("observedState", "ERROR");
            response.putNull("runtimeId");
            response.put("message", failure.getMessage());
            return ResponseEntity.badRequest().body(response);
        } catch (Exception failure) {
            ObjectNode response = objectMapper.createObjectNode();
            response.put("success", false);
            response.put("observedState", "DEGRADED");
            response.putNull("runtimeId");
            response.put("message", "Docker operation failed");
            return ResponseEntity.status(503).body(response);
        }
    }

    static boolean isAuthorizedRequest(String providerId, String instanceId) {
        return ("http-gateway".equals(providerId) || "kafka-messaging".equals(providerId) || "linux-shell".equals(providerId))
                && instanceId != null
                && instanceId.matches("[0-9a-fA-F-]{36}");
    }

    private ResponseEntity<JsonNode> rejectedProviderOrInstance() {
        ObjectNode response = objectMapper.createObjectNode();
        response.put("success", false);
        response.put("observedState", "ERROR");
        response.putNull("runtimeId");
        response.put("message", "Provider or instance is not authorized.");
        return ResponseEntity.badRequest().body(response);
    }

    private interface AgentOperation {
        DockerModuleAgentService.AgentOperationResult run();
    }
}
