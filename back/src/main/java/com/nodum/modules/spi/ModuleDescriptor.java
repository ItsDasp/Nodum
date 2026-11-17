package com.nodum.modules.spi;

import java.util.Set;

/** Stable identity and declared capabilities of a trusted, host-installed module. */
public record ModuleDescriptor(
        String id,
        String version,
        int contractVersion,
        String name,
        String description,
        Set<String> capabilities
) {
    public ModuleDescriptor {
        if (id == null || !id.matches("[a-z][a-z0-9-]{1,62}")) {
            throw new IllegalArgumentException("Module id must be a lowercase slug");
        }
        if (version == null || version.isBlank()) throw new IllegalArgumentException("Module version is required");
        if (contractVersion < 1) throw new IllegalArgumentException("Contract version must be positive");
        if (name == null || name.isBlank()) throw new IllegalArgumentException("Module name is required");
        capabilities = Set.copyOf(capabilities == null ? Set.of() : capabilities);
    }
}
