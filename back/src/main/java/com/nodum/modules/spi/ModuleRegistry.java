package com.nodum.modules.spi;

import org.springframework.stereotype.Component;
import com.nodum.modules.runtime.RemoteModuleAdapter;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
public class ModuleRegistry {
    private final Map<String, ModulePlugin> plugins;

    public ModuleRegistry(List<ModulePlugin> providers) {
        Map<String, ModulePlugin> resolved = new java.util.HashMap<>();
        for (ModulePlugin provider : providers) {
            String id = provider.descriptor().id();
            ModulePlugin current = resolved.get(id);
            if (current == null) {
                resolved.put(id, provider);
            } else if (provider instanceof RemoteModuleAdapter remote && remote.enabled()) {
                resolved.put(id, provider);
            } else if (current instanceof RemoteModuleAdapter remote && !remote.enabled()
                    && !(provider instanceof RemoteModuleAdapter)) {
                resolved.put(id, provider);
            } else if (!(provider instanceof RemoteModuleAdapter)) {
                throw new IllegalStateException("Duplicate module provider: " + id);
            }
        }
        this.plugins = java.util.Collections.unmodifiableMap(resolved);
    }

    public Collection<ModuleDescriptor> catalog() {
        return plugins.values().stream().map(ModulePlugin::descriptor)
                .sorted(Comparator.comparing(ModuleDescriptor::id)).toList();
    }

    public ModulePlugin require(String id) {
        ModulePlugin plugin = plugins.get(id);
        if (plugin == null) throw new IllegalArgumentException("No installed module provider for: " + id);
        return plugin;
    }
}
