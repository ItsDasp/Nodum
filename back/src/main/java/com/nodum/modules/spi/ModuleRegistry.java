package com.nodum.modules.spi;

import org.springframework.stereotype.Component;

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
        this.plugins = providers.stream().collect(Collectors.toUnmodifiableMap(
                provider -> provider.descriptor().id(),
                Function.identity(),
                (first, duplicate) -> {
                    throw new IllegalStateException("Duplicate module provider: " + first.descriptor().id());
                }
        ));
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
