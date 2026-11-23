package com.nodum.modules.runtime;

import com.nodum.modules.spi.ModuleDescriptor;
import com.nodum.modules.spi.ModuleHealth;
import com.nodum.modules.spi.ModuleInstanceContext;
import com.nodum.modules.spi.ModulePlugin;
import com.nodum.modules.spi.ModuleValidation;
import org.springframework.stereotype.Component;

import java.util.Set;

@Component
public class LinuxShellModule implements ModulePlugin {
    private final InfrastructureAgentClient agent;

    public LinuxShellModule(InfrastructureAgentClient agent) { this.agent = agent; }

    @Override public ModuleDescriptor descriptor() {
        return new ModuleDescriptor("linux-shell", "1.0.0", 1, "Shell Linux",
                "Terminal Linux aislada para inspeccionar servicios del mismo proyecto.",
                Set.of("shell.terminal", "project.network"));
    }

    @Override public ModuleValidation validateConfiguration(String configurationJson) {
        if (!agent.enabled()) return ModuleValidation.invalid("El agente Docker no está configurado.");
        return ModuleValidation.ok();
    }

    @Override public void start(ModuleInstanceContext context, String configurationJson) {
        var result = agent.start("linux-shell", context.instanceId(), context.projectId());
        if (!result.success()) throw new InfrastructureAgentClient.InfrastructureAgentException(result.message(), true);
    }

    @Override public void stop(ModuleInstanceContext context) {
        var result = agent.stop("linux-shell", context.instanceId());
        if (!result.success()) throw new InfrastructureAgentClient.InfrastructureAgentException(result.message(), true);
    }

    @Override public ModuleHealth health(ModuleInstanceContext context) {
        var result = agent.inspect("linux-shell", context.instanceId());
        return new ModuleHealth(result.success() ? ModuleHealth.State.RUNNING : ModuleHealth.State.DEGRADED, result.message());
    }
}
