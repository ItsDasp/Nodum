package com.nodum.modules;

import com.nodum.modules.spi.ModuleDescriptor;
import com.nodum.modules.spi.ModuleRegistry;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collection;

@RestController
@RequestMapping("/api/v1/module-catalog")
public class ModuleCatalogController {
    private final ModuleRegistry moduleRegistry;

    public ModuleCatalogController(ModuleRegistry moduleRegistry) {
        this.moduleRegistry = moduleRegistry;
    }

    @GetMapping
    public Collection<ModuleDescriptor> getCatalog() {
        return moduleRegistry.catalog();
    }
}
