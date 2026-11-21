package com.nodum.agent;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.command.CreateContainerCmd;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.model.*;
import com.github.dockerjava.core.command.ExecStartResultCallback;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.httpclient5.ApacheDockerHttpClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.UUID;

@Service
public class DockerModuleAgentService {
    private static final String GATEWAY_PROVIDER = "http-gateway";
    private static final String KAFKA_PROVIDER = "kafka-messaging";
    private static final String SHELL_PROVIDER = "linux-shell";
    private static final String GATEWAY_IMAGE = "nodum-gateway-module:1.0.0";
    private static final String KAFKA_IMAGE = "nodum-kafka-module:1.0.0";
    private static final String SHELL_IMAGE = "nodum-shell-module:1.0.0";
    private static final int MAX_CAPTURE_BYTES = 64 * 1024;

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
        return create(providerId, instanceId, null);
    }

    public synchronized AgentOperationResult create(String providerId, String instanceId, String projectId) {
        validateProviderInstance(providerId, instanceId);
        if (projectId != null) ensureProjectNetwork(projectId);
        InspectContainerResponse existing = findContainer(providerId, instanceId);
        if (existing != null) {
            if (projectId != null) connectToProjectNetwork(existing.getId(), projectId);
            return inspect(providerId, instanceId);
        }

        Map<String, String> labels = labels(providerId, instanceId, projectId);
        Map<String, String> providerEnvironment = GATEWAY_PROVIDER.equals(providerId)
                ? gatewayEnvironment : KAFKA_PROVIDER.equals(providerId) ? kafkaEnvironment : Map.of();
        List<String> environment = providerEnvironment.entrySet().stream()
                .map(entry -> entry.getKey() + "=" + entry.getValue()).toList();

        HostConfig hostConfig = templateHostConfig(providerId, projectId);
        String image = GATEWAY_PROVIDER.equals(providerId) ? GATEWAY_IMAGE
                : KAFKA_PROVIDER.equals(providerId) ? KAFKA_IMAGE : SHELL_IMAGE;
        String name = "nodum-module-" + providerId + "-" + instanceId;

        CreateContainerCmd create = docker.createContainerCmd(image)
                .withName(name)
                .withLabels(labels)
                .withEnv(environment)
                .withHostConfig(hostConfig);
        if (!SHELL_PROVIDER.equals(providerId)) create.withExposedPorts(List.of(new ExposedPort(
                GATEWAY_PROVIDER.equals(providerId) ? 8081 : 8082, InternetProtocol.TCP)));
        CreateContainerResponse created = create.exec();
        docker.startContainerCmd(created.getId()).exec();
        if (projectId != null && !SHELL_PROVIDER.equals(providerId)) connectToProjectNetwork(created.getId(), projectId);
        if (SHELL_PROVIDER.equals(providerId) && projectId != null) connectProjectSiblings(projectId);
        return inspect(providerId, instanceId);
    }

    public synchronized AgentOperationResult start(String providerId, String instanceId) {
        return start(providerId, instanceId, null);
    }

    public synchronized AgentOperationResult start(String providerId, String instanceId, String projectId) {
        validateProviderInstance(providerId, instanceId);
        InspectContainerResponse existing = findContainer(providerId, instanceId);
        if (existing == null) return create(providerId, instanceId, projectId);
        if (projectId != null) connectToProjectNetwork(existing.getId(), projectId);
        String state = existing.getState() == null ? null : existing.getState().getStatus();
        if (!"running".equalsIgnoreCase(state)) docker.startContainerCmd(existing.getId()).exec();
        if (SHELL_PROVIDER.equals(providerId) && projectId != null) connectProjectSiblings(projectId);
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

    public RuntimeCommandResult logs(String providerId, String instanceId, int requestedTail) {
        validateProviderInstance(providerId, instanceId);
        int tail = Math.max(1, Math.min(requestedTail, 500));
        InspectContainerResponse container = findContainer(providerId, instanceId);
        if (container == null) throw new IllegalArgumentException("El contenedor del módulo no existe.");
        CappedOutputStream output = new CappedOutputStream(MAX_CAPTURE_BYTES);
        try {
            docker.logContainerCmd(container.getId()).withStdOut(true).withStdErr(true).withTail(tail)
                    .exec(new com.github.dockerjava.api.async.ResultCallback.Adapter<Frame>() {
                        @Override public void onNext(Frame frame) {
                            try { output.write(frame.getPayload()); }
                            catch (IOException ignored) { }
                        }
                    }).awaitCompletion(5, TimeUnit.SECONDS);
            return new RuntimeCommandResult(output.text(), null, false, output.truncated(), null);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Se interrumpió la lectura de registros.");
        } catch (Exception failure) {
            throw new IllegalStateException("No se pudieron leer los registros del contenedor.");
        }
    }

    public String requireRunningShell(String instanceId, String projectId) {
        validateProviderInstance(SHELL_PROVIDER, instanceId);
        projectNetwork(projectId);
        InspectContainerResponse container = findContainer(SHELL_PROVIDER, instanceId);
        if (container == null || container.getState() == null || !Boolean.TRUE.equals(container.getState().getRunning())) {
            throw new IllegalStateException("El contenedor Shell Linux no está en ejecución.");
        }
        if (!projectId.equals(container.getConfig().getLabels().get("nodum.project-id"))) {
            throw new IllegalArgumentException("La shell no pertenece al proyecto solicitado.");
        }
        return container.getId();
    }

    public RuntimeCommandResult execute(String providerId, String instanceId, String command, String workingDirectory) {
        validateProviderInstance(providerId, instanceId);
        if (command == null || command.isBlank() || command.length() > 8192 || command.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("Escribe un comando o script de hasta 8192 caracteres.");
        }
        if (workingDirectory == null || workingDirectory.isBlank()) workingDirectory = "/app";
        if (workingDirectory.length() > 4096 || workingDirectory.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("La ruta de trabajo no es válida.");
        }
        InspectContainerResponse container = findContainer(providerId, instanceId);
        if (container == null || container.getState() == null || !Boolean.TRUE.equals(container.getState().getRunning())) {
            throw new IllegalStateException("El contenedor del módulo debe estar en ejecución para usar la consola.");
        }

        String marker = "__NODUM_CWD_" + UUID.randomUUID().toString().replace("-", "") + "__";
        String script = "cd -- \"$NODUM_SESSION_CWD\" || exit 125\n" + command
                + "\nnodum_status=$?\nprintf '\\n" + marker + "%s\\n' \"$PWD\"\nexit \"$nodum_status\"";
        String execId = docker.execCreateCmd(container.getId())
                .withCmd("timeout", "30s", "/bin/sh", "-c", script)
                .withEnv(List.of("NODUM_SESSION_CWD=" + workingDirectory))
                .withAttachStdout(true).withAttachStderr(true).withTty(false)
                .withUser("nodum")
                .exec().getId();
        CappedOutputStream output = new CappedOutputStream(MAX_CAPTURE_BYTES);
        try {
            boolean completed = docker.execStartCmd(execId)
                    .exec(new ExecStartResultCallback(output, output))
                    .awaitCompletion(32, TimeUnit.SECONDS);
            Long exitCode = docker.inspectExecCmd(execId).exec().getExitCodeLong();
            boolean timedOut = !completed || Long.valueOf(124L).equals(exitCode);
            String result = output.text();
            String nextDirectory = workingDirectory;
            int markerAt = result.lastIndexOf(marker);
            if (markerAt >= 0) {
                int valueStart = markerAt + marker.length();
                int valueEnd = result.indexOf('\n', valueStart);
                if (valueEnd < 0) valueEnd = result.length();
                nextDirectory = result.substring(valueStart, valueEnd).trim();
                int lineStart = result.lastIndexOf('\n', markerAt - 1);
                result = result.substring(0, Math.max(0, lineStart)) + result.substring(valueEnd);
            }
            return new RuntimeCommandResult(result, exitCode == null ? null : exitCode.intValue(), timedOut, output.truncated(), nextDirectory);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Se interrumpió el comando remoto.");
        } catch (Exception failure) {
            throw new IllegalStateException("No se pudo ejecutar el comando en el contenedor del módulo.");
        }
    }

    private InspectContainerResponse findContainer(String providerId, String instanceId) {
        return docker.listContainersCmd().withShowAll(true).exec().stream()
                .filter(container -> providerId.equals(container.getLabels().get("nodum.provider-id")))
                .filter(container -> instanceId.equals(container.getLabels().get("nodum.instance-id")))
                .map(container -> docker.inspectContainerCmd(container.getId()).exec())
                .findFirst()
                .orElse(null);
    }

    private Map<String, String> labels(String providerId, String instanceId, String projectId) {
        Map<String, String> values = new java.util.HashMap<>();
        values.put("nodum.managed", "true"); values.put("nodum.provider-id", providerId);
        values.put("nodum.instance-id", instanceId); values.put("nodum.contract-version", "1");
        if (projectId != null) values.put("nodum.project-id", projectId);
        return Map.copyOf(values);
    }

    private HostConfig templateHostConfig(String providerId, String projectId) {
        if (SHELL_PROVIDER.equals(providerId)) {
            if (projectId == null) throw new IllegalArgumentException("Project ID is required for the Linux shell.");
            return HostConfig.newHostConfig().withNetworkMode(projectNetwork(projectId))
                    .withMemory(512L * 1024 * 1024).withMemorySwap(512L * 1024 * 1024)
                    .withCpuQuota(50_000L).withCpuPeriod(100_000L).withPidsLimit(128L)
                    .withReadonlyRootfs(false).withPrivileged(false).withBinds(List.of())
                    .withCapDrop(Capability.ALL).withSecurityOpts(List.of("no-new-privileges:true"))
                    .withRestartPolicy(RestartPolicy.noRestart());
        }
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
        if (!GATEWAY_PROVIDER.equals(providerId) && !KAFKA_PROVIDER.equals(providerId) && !SHELL_PROVIDER.equals(providerId)) {
            throw new IllegalArgumentException("Provider is not authorized.");
        }
        if (instanceId == null || !instanceId.matches("[0-9a-fA-F-]{36}")) {
            throw new IllegalArgumentException("Instance ID is invalid.");
        }
    }

    private String projectNetwork(String projectId) {
        if (projectId == null || !projectId.matches("[0-9a-fA-F-]{36}")) throw new IllegalArgumentException("Project ID is invalid.");
        return "nodum-project-" + projectId.replace("-", "").toLowerCase();
    }

    private synchronized void ensureProjectNetwork(String projectId) {
        String name = projectNetwork(projectId);
        boolean exists = docker.listNetworksCmd().withNameFilter(name).exec().stream().anyMatch(n -> name.equals(n.getName()));
        if (!exists) docker.createNetworkCmd().withName(name).withDriver("bridge")
                .withLabels(Map.of("nodum.managed", "true", "nodum.project-id", projectId)).exec();
    }

    private void connectToProjectNetwork(String containerId, String projectId) {
        ensureProjectNetwork(projectId);
        String networkName = projectNetwork(projectId);
        try { docker.connectToNetworkCmd().withContainerId(containerId).withNetworkId(networkName)
                .withContainerNetwork(new com.github.dockerjava.api.model.ContainerNetwork().withAliases(List.of("module-" + containerId.substring(0, 12)))).exec(); }
        catch (com.github.dockerjava.api.exception.NotFoundException ignored) { }
        catch (com.github.dockerjava.api.exception.ConflictException ignored) { }
    }

    private void connectProjectSiblings(String projectId) {
        docker.listContainersCmd().withShowAll(true).exec().stream()
                .filter(c -> "true".equals(c.getLabels().get("nodum.managed")))
                .filter(c -> projectId.equals(c.getLabels().get("nodum.project-id")))
                .filter(c -> !SHELL_PROVIDER.equals(c.getLabels().get("nodum.provider-id")))
                .forEach(c -> connectToProjectNetwork(c.getId(), projectId));
    }

    public record AgentOperationResult(boolean success, String observedState, String runtimeId, String message) {}
    public record RuntimeCommandResult(String output, Integer exitCode, boolean timedOut, boolean truncated, String workingDirectory) {}

    private static final class CappedOutputStream extends OutputStream {
        private final int maximumBytes;
        private final ByteArrayOutputStream captured = new ByteArrayOutputStream();
        private boolean truncated;

        private CappedOutputStream(int maximumBytes) { this.maximumBytes = maximumBytes; }

        @Override public synchronized void write(int value) {
            if (captured.size() < maximumBytes) captured.write(value);
            else truncated = true;
        }

        @Override public synchronized void write(byte[] bytes, int offset, int length) {
            int remaining = maximumBytes - captured.size();
            if (remaining > 0) captured.write(bytes, offset, Math.min(remaining, length));
            if (length > remaining) truncated = true;
        }

        private synchronized String text() {
            String result = captured.toString(StandardCharsets.UTF_8);
            return truncated ? result + "\n[Salida recortada a 64 KiB]\n" : result;
        }

        private synchronized boolean truncated() { return truncated; }
    }
}
