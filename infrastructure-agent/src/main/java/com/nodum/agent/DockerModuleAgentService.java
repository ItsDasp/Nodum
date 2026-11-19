package com.nodum.agent;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.model.*;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.httpclient5.ApacheDockerHttpClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.Map;

@Service
public class DockerModuleAgentService {
    private static final String GATEWAY_PROVIDER = "http-gateway";
    private static final String KAFKA_PROVIDER = "kafka-messaging";
    private static final String GATEWAY_IMAGE = "nodum-gateway-module:1.0.0";
    private static final String KAFKA_IMAGE = "nodum-kafka-module:1.0.0";

    private final DockerClient docker;
    private final String network;
    private final Map<String, String> gatewayEnvironment;
    private final Map<String, String> kafkaEnvironment;

    public DockerModuleAgentService(
            @Value("${nodum.docker.network:nodum-network}") String network,
            @Value("${NODUM_GATEWAY_ALLOWED_HOSTS:}") String allowedHosts,
            @Value("${NODUM_GATEWAY_ALLOW_PRIVATE_ADDRESSES:false}") boolean allowPrivate,
            @Value("${NODUM_MODULE_SERVICE_TOKEN:}") String moduleToken,
            @Value("${NODUM_KAFKA_REMOTE_TOKEN:}") String kafkaToken,
            @Value("${NODUM_KAFKA_BROKERS:kafka:9092}") String kafkaBrokers) {
        this.network = network;
        this.gatewayEnvironment = Map.of(
                "NODUM_GATEWAY_ALLOWED_HOSTS", allowedHosts,
                "NODUM_GATEWAY_ALLOW_PRIVATE_ADDRESSES", Boolean.toString(allowPrivate),
                "NODUM_MODULE_SERVICE_TOKEN", moduleToken);
        this.kafkaEnvironment = Map.of(
                "NODUM_KAFKA_BROKERS", kafkaBrokers,
                "NODUM_MODULE_SERVICE_TOKEN", kafkaToken);

        DefaultDockerClientConfig config = DefaultDockerClientConfig.createDefaultConfigBuilder()
                .withDockerHost("unix:///var/run/docker.sock")
                .build();
        ApacheDockerHttpClient httpClient = new ApacheDockerHttpClient.Builder()
                .dockerHost(config.getDockerHost())
                .sslConfig(config.getSSLConfig())
                .maxConnections(20)
                .connectionTimeout(Duration.ofSeconds(3))
                .responseTimeout(Duration.ofSeconds(30))
                .build();
        this.docker = DockerClientImpl.getInstance(config, httpClient);
    }

    public synchronized AgentOperationResult create(String providerId, String instanceId) {
        validateProviderInstance(providerId, instanceId);
        InspectContainerResponse existing = findContainer(providerId, instanceId);
        if (existing != null) return inspect(providerId, instanceId);

        Map<String, String> labels = labels(providerId, instanceId);
        Map<String, String> providerEnvironment = GATEWAY_PROVIDER.equals(providerId)
                ? gatewayEnvironment : kafkaEnvironment;
        List<String> environment = providerEnvironment.entrySet().stream()
                .map(entry -> entry.getKey() + "=" + entry.getValue()).toList();

        HostConfig hostConfig = templateHostConfig(providerId);
        String image = GATEWAY_PROVIDER.equals(providerId) ? GATEWAY_IMAGE : KAFKA_IMAGE;
        String name = GATEWAY_PROVIDER.equals(providerId)
                ? "nodum-module-http-gateway-" + instanceId
                : "nodum-module-kafka-messaging-" + instanceId;

        CreateContainerResponse created = docker.createContainerCmd(image)
                .withName(name)
                .withLabels(labels)
                .withEnv(environment)
                .withExposedPorts(List.of(new ExposedPort(
                        GATEWAY_PROVIDER.equals(providerId) ? 8081 : 8082,
                        InternetProtocol.TCP)))
                .withHostConfig(hostConfig)
                .exec();
        docker.startContainerCmd(created.getId()).exec();
        return inspect(providerId, instanceId);
    }

    public synchronized AgentOperationResult start(String providerId, String instanceId) {
        validateProviderInstance(providerId, instanceId);
        InspectContainerResponse existing = findContainer(providerId, instanceId);
        if (existing == null) return create(providerId, instanceId);
        String state = existing.getState() == null ? null : existing.getState().getStatus();
        if (!"running".equalsIgnoreCase(state)) docker.startContainerCmd(existing.getId()).exec();
        return inspect(providerId, instanceId);
    }

    public synchronized AgentOperationResult stop(String providerId, String instanceId) {
        validateProviderInstance(providerId, instanceId);
        InspectContainerResponse existing = findContainer(providerId, instanceId);
        if (existing == null) return new AgentOperationResult(true, "STOPPED", null,
                "Container already stopped");
        String state = existing.getState() == null ? null : existing.getState().getStatus();
        if ("running".equalsIgnoreCase(state)) {
            docker.stopContainerCmd(existing.getId()).withTimeout(10).exec();
        }
        return new AgentOperationResult(true, "STOPPED", existing.getId(),
                "Container stopped");
    }

    public synchronized AgentOperationResult restart(String providerId, String instanceId) {
        validateProviderInstance(providerId, instanceId);
        InspectContainerResponse existing = findContainer(providerId, instanceId);
        if (existing == null) return new AgentOperationResult(false, "DEGRADED", null,
                "Container does not exist");
        docker.restartContainerCmd(existing.getId()).withTimeout(10).exec();
        return inspect(providerId, instanceId);
    }

    public AgentOperationResult inspect(String providerId, String instanceId) {
        validateProviderInstance(providerId, instanceId);
        InspectContainerResponse existing = findContainer(providerId, instanceId);
        if (existing == null) return new AgentOperationResult(false, "DEGRADED", null,
                "Container is missing");

        InspectContainerResponse.ContainerState state = existing.getState();
        String status = state == null || state.getStatus() == null ? "UNKNOWN" : state.getStatus().toUpperCase();
        boolean running = state != null && Boolean.TRUE.equals(state.getRunning());
        return new AgentOperationResult(running, running ? "RUNNING" : status, existing.getId(),
                running ? "Container is running" : "Container is not running");
    }

    private InspectContainerResponse findContainer(String providerId, String instanceId) {
        return docker.listContainersCmd().withShowAll(true).exec().stream()
                .filter(container -> providerId.equals(container.getLabels().get("nodum.provider-id")))
                .filter(container -> instanceId.equals(container.getLabels().get("nodum.instance-id")))
                .map(container -> docker.inspectContainerCmd(container.getId()).exec())
                .findFirst()
                .orElse(null);
    }

    private Map<String, String> labels(String providerId, String instanceId) {
        return Map.of(
                "nodum.managed", "true",
                "nodum.provider-id", providerId,
                "nodum.instance-id", instanceId,
                "nodum.contract-version", "1");
    }

    private HostConfig templateHostConfig(String providerId) {
        if (GATEWAY_PROVIDER.equals(providerId)) {
            return HostConfig.newHostConfig().withNetworkMode(network)
                    .withMemory(512L * 1024 * 1024).withMemorySwap(512L * 1024 * 1024)
                    .withCpuQuota(75_000L).withCpuPeriod(100_000L).withPidsLimit(128L)
                    .withReadonlyRootfs(false).withPrivileged(false).withBinds(List.of())
                    .withRestartPolicy(RestartPolicy.noRestart());
        }
        return HostConfig.newHostConfig().withNetworkMode(network)
                .withMemory(256L * 1024 * 1024).withMemorySwap(256L * 1024 * 1024)
                .withCpuQuota(25_000L).withCpuPeriod(100_000L).withPidsLimit(64L)
                .withReadonlyRootfs(false).withPrivileged(false).withBinds(List.of())
                .withRestartPolicy(RestartPolicy.noRestart());
    }

    private void validateProviderInstance(String providerId, String instanceId) {
        if (!GATEWAY_PROVIDER.equals(providerId) && !KAFKA_PROVIDER.equals(providerId)) {
            throw new IllegalArgumentException("Provider is not authorized.");
        }
        if (instanceId == null || !instanceId.matches("[0-9a-fA-F-]{36}")) {
            throw new IllegalArgumentException("Instance ID is invalid.");
        }
    }

    public record AgentOperationResult(boolean success, String observedState, String runtimeId, String message) {}
}
