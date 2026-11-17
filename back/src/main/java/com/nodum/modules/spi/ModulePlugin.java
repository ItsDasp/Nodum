package com.nodum.modules.spi;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Extension point for trusted modules installed with the host application.
 * In-process implementations share the host's failure and security boundary.
 */
public interface ModulePlugin {
    ModuleDescriptor descriptor();

    ModuleValidation validateConfiguration(String configurationJson);

    void start(ModuleInstanceContext context, String configurationJson);

    void stop(ModuleInstanceContext context);

    ModuleHealth health(ModuleInstanceContext context);

    default JsonNode execute(ModuleInstanceContext context, String action, JsonNode input) {
        throw new UnsupportedOperationException("Module does not support action: " + action);
    }
}
