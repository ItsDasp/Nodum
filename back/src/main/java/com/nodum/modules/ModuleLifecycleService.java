package com.nodum.modules;

import com.fasterxml.jackson.databind.JsonNode;
import com.nodum.modules.spi.ModuleHealth;
import com.nodum.modules.spi.ModuleInstanceContext;
import com.nodum.modules.spi.ModulePlugin;
import com.nodum.modules.spi.ModuleRegistry;
import com.nodum.modules.spi.ModuleValidation;
import com.nodum.modules.runtime.RemoteModuleClient;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.UUID;

@Service
public class ModuleLifecycleService {
    private final ModuleRegistry registry;
    private final ModuleRepository moduleRepository;
    private final ConcurrentMap<UUID, Object> lifecycleLocks = new ConcurrentHashMap<>();

    public ModuleLifecycleService(ModuleRegistry registry, ModuleRepository moduleRepository) {
        this.registry = registry;
        this.moduleRepository = moduleRepository;
    }

    public Module start(Module module) {
        synchronized (lifecycleLocks.computeIfAbsent(module.getId(), ignored -> new Object())) {
            return startLocked(module);
        }
    }

    private Module startLocked(Module module) {
        ModulePlugin provider = requireProvider(module);
        ModuleValidation validation = provider.validateConfiguration(module.getConfigurationJson());
        if (!validation.valid()) throw new InvalidModuleConfigurationException(validation.errors());

        if ("RUNNING".equals(module.getLifecycleState())
                && provider.health(context(module)).state() == ModuleHealth.State.RUNNING) return module;

        module.setDesiredState("RUNNING");
        module.setObservedState("STARTING");
        module.setLifecycleState("STARTING");
        module.setLifecycleError(null);
        module.setRuntimeError(null);
        moduleRepository.save(module);
        try {
            provider.start(context(module), module.getConfigurationJson());
            module.setObservedState("RUNNING");
            module.setLifecycleState("RUNNING");
            module.setLastObservedAt(java.time.LocalDateTime.now());
        } catch (RuntimeException failure) {
            try { provider.stop(context(module)); } catch (RuntimeException ignored) { }
            module.setObservedState("ERROR");
            module.setLifecycleState("ERROR");
            module.setLifecycleError("No se pudo iniciar el módulo. Revisa su configuración y la disponibilidad del proveedor.");
            module.setRuntimeError(failure instanceof RemoteModuleClient.RemoteModuleException
                    ? failure.getMessage() : null);
        }
        return moduleRepository.save(module);
    }

    public Module stop(Module module) {
        synchronized (lifecycleLocks.computeIfAbsent(module.getId(), ignored -> new Object())) {
            return stopLocked(module);
        }
    }

    private Module stopLocked(Module module) {
        ModulePlugin provider = requireProvider(module);
        try {
            provider.stop(context(module));
            module.setDesiredState("STOPPED");
            module.setObservedState("STOPPED");
            module.setLifecycleState("STOPPED");
            module.setRuntimeError(null);
            module.setLifecycleError(null);
        } catch (RuntimeException failure) {
            module.setObservedState("ERROR");
            module.setLifecycleState("ERROR");
            module.setLifecycleError("No se pudo detener el módulo. Revisa los registros del proveedor.");
            module.setRuntimeError(failure instanceof RemoteModuleClient.RemoteModuleException
                    ? failure.getMessage() : null);
        }
        return moduleRepository.save(module);
    }

    public ModuleHealth health(Module module) {
        return requireProvider(module).health(context(module));
    }

    public JsonNode execute(Module module, String action, JsonNode input) {
        if (!"RUNNING".equals(module.getLifecycleState())) {
            throw new IllegalStateException("Inicia el módulo antes de ejecutar sus acciones.");
        }
        return requireProvider(module).execute(context(module), action, input);
    }

    public ModuleValidation validate(Module module) {
        return requireProvider(module).validateConfiguration(module.getConfigurationJson());
    }

    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void restoreRunningModules() {
        for (Module module : moduleRepository.findAll()) {
            if (!"RUNNING".equals(module.getLifecycleState())) continue;
            try {
                ModulePlugin provider = requireProvider(module);
                ModuleValidation validation = provider.validateConfiguration(module.getConfigurationJson());
                if (!validation.valid()) throw new InvalidModuleConfigurationException(validation.errors());
                provider.start(context(module), module.getConfigurationJson());
            } catch (RuntimeException failure) {
                module.setLifecycleState("ERROR");
                module.setLifecycleError("No se pudo restaurar el módulo tras reiniciar Nodum. Revisa su proveedor y configuración.");
                moduleRepository.save(module);
            }
        }
    }

    private ModulePlugin requireProvider(Module module) {
        if (module.getProviderId() == null || module.getProviderId().isBlank()) {
            throw new IllegalStateException("This metadata-only module has no executable provider");
        }
        ModulePlugin provider = registry.require(module.getProviderId());
        if (!provider.descriptor().version().equals(module.getProviderVersion())) {
            throw new IllegalStateException("Installed provider version does not match this module instance");
        }
        return provider;
    }

    private ModuleInstanceContext context(Module module) {
        return new ModuleInstanceContext(module.getProject().getId(), module.getId());
    }

    public static class InvalidModuleConfigurationException extends RuntimeException {
        private final java.util.List<String> errors;
        public InvalidModuleConfigurationException(java.util.List<String> errors) {
            super("Module configuration is invalid");
            this.errors = java.util.List.copyOf(errors);
        }
        public java.util.List<String> getErrors() { return errors; }
    }
}
