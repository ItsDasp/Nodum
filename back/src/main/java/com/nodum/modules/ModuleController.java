package com.nodum.modules;

import com.fasterxml.jackson.databind.JsonNode;
import com.nodum.modules.spi.ModuleHealth;
import com.nodum.modules.spi.ModuleRegistry;
import com.nodum.modules.spi.ModuleValidation;
import com.nodum.identity.User;
import com.nodum.identity.UserRepository;
import com.nodum.projects.Project;
import com.nodum.projects.ProjectRepository;
import com.nodum.modules.runtime.InfrastructureAgentClient;
import com.nodum.modules.runtime.ShellSessionTickets;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/modules")
public class ModuleController {
    
    private final ModuleRepository moduleRepository;
    private final UserRepository userRepository;
    private final ProjectRepository projectRepository;
    private final ModuleRegistry moduleRegistry;
    private final ModuleLifecycleService lifecycleService;
    private final InfrastructureAgentClient infrastructureAgent;
    private final ShellSessionTickets shellTickets;
    
    public ModuleController(ModuleRepository moduleRepository, UserRepository userRepository, ProjectRepository projectRepository, ModuleRegistry moduleRegistry, ModuleLifecycleService lifecycleService, InfrastructureAgentClient infrastructureAgent, ShellSessionTickets shellTickets) {
        this.moduleRepository = moduleRepository;
        this.userRepository = userRepository;
        this.projectRepository = projectRepository;
        this.moduleRegistry = moduleRegistry;
        this.lifecycleService = lifecycleService;
        this.infrastructureAgent = infrastructureAgent;
        this.shellTickets = shellTickets;
    }
    
    private Optional<User> getCurrentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return Optional.empty();
        }
        String username = authentication.getName();
        return userRepository.findByUsername(username);
    }
    
    @GetMapping
    public ResponseEntity<List<Module>> getModules(@PathVariable UUID projectId) {
        Optional<User> userOpt = getCurrentUser();
        if (userOpt.isEmpty()) return ResponseEntity.status(401).build();
        List<Module> modules = moduleRepository.findByProjectIdAndOwnerId(projectId, userOpt.get().getId());
        return ResponseEntity.ok(modules);
    }
    
    @PostMapping
    public ResponseEntity<?> createModule(
            @PathVariable UUID projectId,
            @Valid @RequestBody CreateModuleRequest request) {
        Optional<User> userOpt = getCurrentUser();
        if (userOpt.isEmpty()) {
            return ResponseEntity.status(401).build();
        }
        User user = userOpt.get();
        
        Project project = projectRepository.findById(projectId)
                .filter(candidate -> candidate.getOwner().getId().equals(user.getId()))
                .orElse(null);
        if (project == null) return ResponseEntity.notFound().build();
        
        if (moduleRepository.existsByNameAndProjectId(request.name(), projectId)) {
            return ResponseEntity.badRequest().build();
        }

        String configuration = request.configurationJson() == null || request.configurationJson().isBlank()
                ? "{}" : request.configurationJson();
        String providerVersion = null;
        if (request.providerId() != null && !request.providerId().isBlank()) {
            var provider = moduleRegistry.require(request.providerId());
            var validation = provider.validateConfiguration(configuration);
            if (!validation.valid()) return ResponseEntity.unprocessableEntity().body(java.util.Map.of("errors", validation.errors()));
            providerVersion = provider.descriptor().version();
        } else if (!"{}".equals(configuration.trim())) {
            return ResponseEntity.badRequest().body(java.util.Map.of("message", "La configuración ejecutable requiere un proveedor instalado."));
        }
        
        Module module = new Module();
        module.setName(request.name());
        module.setDescription(request.description());
        module.setBasePath(request.basePath());
        module.setVersion(request.version());
        module.setProviderId(request.providerId());
        module.setProviderVersion(providerVersion);
        module.setConfigurationJson(configuration);
        module.setProject(project);
        module.setOwner(user);
        
        Module saved = moduleRepository.save(module);
        return ResponseEntity.ok(saved);
    }

    @PostMapping("/{id}/start")
    public ResponseEntity<Module> startModule(@PathVariable UUID projectId, @PathVariable UUID id) {
        return ownedModule(projectId, id).map(module -> ResponseEntity.ok(lifecycleService.start(module)))
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/{id}/stop")
    public ResponseEntity<Module> stopModule(@PathVariable UUID projectId, @PathVariable UUID id) {
        return ownedModule(projectId, id).map(module -> ResponseEntity.ok(lifecycleService.stop(module)))
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/{id}/health")
    public ResponseEntity<ModuleHealth> moduleHealth(@PathVariable UUID projectId, @PathVariable UUID id) {
        return ownedModule(projectId, id).map(module -> ResponseEntity.ok(lifecycleService.health(module)))
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/{id}/validation")
    public ResponseEntity<ModuleValidation> validateModule(@PathVariable UUID projectId, @PathVariable UUID id) {
        return ownedModule(projectId, id).map(module -> ResponseEntity.ok(lifecycleService.validate(module)))
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/{id}/actions/{action}")
    public ResponseEntity<JsonNode> executeAction(@PathVariable UUID projectId, @PathVariable UUID id,
                                                   @PathVariable String action, @RequestBody(required = false) JsonNode input) {
        return ownedModule(projectId, id).map(module -> ResponseEntity.ok(lifecycleService.execute(module, action, input)))
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/{id}/logs")
    public ResponseEntity<InfrastructureAgentClient.RuntimeCommandResult> getModuleLogs(
            @PathVariable UUID projectId, @PathVariable UUID id,
            @RequestParam(defaultValue = "200") int tail) {
        Module module = ownedModule(projectId, id).orElse(null);
        if (module == null) return ResponseEntity.notFound().build();
        requireManagedRuntime(module);
        if (!"linux-shell".equals(module.getProviderId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "La consola está disponible en módulos Shell Linux.");
        }
        return ResponseEntity.ok(infrastructureAgent.logs(module.getProviderId(), module.getId(), tail));
    }

    @PostMapping("/{id}/shell")
    public ResponseEntity<InfrastructureAgentClient.RuntimeCommandResult> executeShellCommand(
            @PathVariable UUID projectId, @PathVariable UUID id,
            @Valid @RequestBody ShellCommandRequest request) {
        Module module = ownedModule(projectId, id).orElse(null);
        if (module == null) return ResponseEntity.notFound().build();
        requireManagedRuntime(module);
        if (!"RUNNING".equals(module.getLifecycleState())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Inicia el módulo antes de usar la consola remota.");
        }
        return ResponseEntity.ok(infrastructureAgent.execute(module.getProviderId(), module.getId(), request.command(), request.workingDirectory()));
    }

    @PostMapping("/{id}/shell/session")
    public ResponseEntity<?> createShellSession(@PathVariable UUID projectId, @PathVariable UUID id) {
        Module module = ownedModule(projectId, id).orElse(null);
        if (module == null) return ResponseEntity.notFound().build();
        if (!"linux-shell".equals(module.getProviderId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "El terminal interactivo requiere un módulo Shell Linux.");
        }
        if (!"RUNNING".equals(module.getLifecycleState())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Inicia el módulo Shell Linux antes de abrir el terminal.");
        }
        if (!infrastructureAgent.enabled()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "El agente de infraestructura no está disponible.");
        return ResponseEntity.ok(java.util.Map.of("ticket", shellTickets.issue(projectId, id)));
    }

    private void requireManagedRuntime(Module module) {
        if (!List.of("http-gateway", "kafka-messaging", "linux-shell").contains(module.getProviderId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Los registros y la consola remota requieren un proveedor ejecutado en contenedor.");
        }
        if (!infrastructureAgent.enabled()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "El agente de infraestructura no está disponible.");
        }
    }

    private Optional<Module> ownedModule(UUID projectId, UUID id) {
        Optional<User> user = getCurrentUser();
        if (user.isEmpty()) return Optional.empty();
        return moduleRepository.findWithProjectById(id)
                .filter(module -> module.getProject().getId().equals(projectId))
                .filter(module -> module.getOwner().getId().equals(user.get().getId()));
    }
    
    @GetMapping("/{id}")
    public ResponseEntity<Module> getModule(
            @PathVariable UUID projectId,
            @PathVariable UUID id) {
        Optional<User> userOpt = getCurrentUser();
        if (userOpt.isEmpty()) {
            return ResponseEntity.status(401).build();
        }
        User user = userOpt.get();
        
        return moduleRepository.findById(id)
                .filter(m -> m.getProject().getId().equals(projectId))
                .filter(m -> m.getOwner().getId().equals(user.getId()))
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }
    
    @PutMapping("/{id}")
    public ResponseEntity<Module> updateModule(
            @PathVariable UUID projectId,
            @PathVariable UUID id,
            @Valid @RequestBody UpdateModuleRequest request) {
        Optional<User> userOpt = getCurrentUser();
        if (userOpt.isEmpty()) {
            return ResponseEntity.status(401).build();
        }
        User user = userOpt.get();
        
        return moduleRepository.findById(id)
                .filter(m -> m.getProject().getId().equals(projectId))
                .filter(m -> m.getOwner().getId().equals(user.getId()))
                .map(module -> {
                    if (request.name() != null) {
                        module.setName(request.name());
                    }
                    if (request.description() != null) {
                        module.setDescription(request.description());
                    }
                    if (request.basePath() != null) {
                        module.setBasePath(request.basePath());
                    }
                    if (request.version() != null) {
                        module.setVersion(request.version());
                    }
                    return ResponseEntity.ok(moduleRepository.save(module));
                })
                .orElse(ResponseEntity.notFound().build());
    }
    
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteModule(
            @PathVariable UUID projectId,
            @PathVariable UUID id) {
        Optional<User> userOpt = getCurrentUser();
        if (userOpt.isEmpty()) {
            return ResponseEntity.status(401).build();
        }
        User user = userOpt.get();
        
        return moduleRepository.findById(id)
                .filter(m -> m.getProject().getId().equals(projectId))
                .filter(m -> m.getOwner().getId().equals(user.getId()))
                .map(module -> {
                    if (List.of("RUNNING", "STARTING", "ERROR").contains(module.getLifecycleState())) {
                        throw new IllegalStateException("Stop the module before deleting it");
                    }
                    moduleRepository.delete(module);
                    return ResponseEntity.noContent().<Void>build();
                })
                .orElse(ResponseEntity.notFound().build());
    }
    
    record CreateModuleRequest(@NotBlank @Size(max = 120) String name,
                               @Size(max = 1000) String description,
                               @NotBlank @Pattern(regexp = "/[A-Za-z0-9/_{}-]*") String basePath,
                               @Size(max = 50) String version,
                               @Size(max = 100) String providerId,
                               @Size(max = 100000) String configurationJson) {}
    record UpdateModuleRequest(@Size(min = 1, max = 120) String name,
                               @Size(max = 1000) String description,
                               @Pattern(regexp = "/[A-Za-z0-9/_{}-]*") String basePath,
                               @Size(max = 50) String version) {}
    record ShellCommandRequest(@NotBlank @Size(max = 8192) String command, @Size(max = 4096) String workingDirectory) {}
}
