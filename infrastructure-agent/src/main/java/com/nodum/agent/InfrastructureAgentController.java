package com.nodum.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

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
        if (!isAuthorizedRequest(providerId, instanceId)) {
            return rejectedProviderOrInstance();
        }
        return operate(() -> service.create(providerId, instanceId));
    }

    @PostMapping("/start")
    public ResponseEntity<JsonNode> start(@RequestBody JsonNode request) {
        String providerId = request.path("providerId").asText();
        String instanceId = request.path("instanceId").asText();
        if (!isAuthorizedRequest(providerId, instanceId)) return rejectedProviderOrInstance();
        return operate(() -> service.start(providerId, instanceId));
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
        return ("http-gateway".equals(providerId) || "kafka-messaging".equals(providerId))
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
