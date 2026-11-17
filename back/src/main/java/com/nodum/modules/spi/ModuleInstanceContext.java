package com.nodum.modules.spi;

import java.util.UUID;

public record ModuleInstanceContext(UUID projectId, UUID instanceId) {
    public ModuleInstanceContext {
        if (projectId == null || instanceId == null) throw new IllegalArgumentException("Project and instance IDs are required");
    }
}
