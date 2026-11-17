package com.nodum.projects;

import com.nodum.identity.User;
import com.nodum.identity.UserRepository;
import com.nodum.modules.ModuleRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/projects")
public class ProjectController {
    
    private final ProjectRepository projectRepository;
    private final UserRepository userRepository;
    private final ModuleRepository moduleRepository;
    
    public ProjectController(ProjectRepository projectRepository, UserRepository userRepository, ModuleRepository moduleRepository) {
        this.projectRepository = projectRepository;
        this.userRepository = userRepository;
        this.moduleRepository = moduleRepository;
    }
    
    private User getCurrentUser(Authentication authentication) {
        String username = authentication.getName();
        return userRepository.findByUsername(username)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));
    }
    
    @GetMapping
    public ResponseEntity<List<Project>> getProjects(Authentication authentication) {
        User user = getCurrentUser(authentication);
        List<Project> projects = projectRepository.findByOwnerId(user.getId());
        return ResponseEntity.ok(projects);
    }
    
    @PostMapping
    public ResponseEntity<Project> createProject(
            @RequestBody CreateProjectRequest request,
            Authentication authentication) {
        User user = getCurrentUser(authentication);
        
        Project project = new Project();
        project.setName(request.name());
        project.setDescription(request.description());
        project.setOwner(user);
        
        Project saved = projectRepository.save(project);
        return ResponseEntity.ok(saved);
    }
    
    @GetMapping("/{id}")
    public ResponseEntity<Project> getProject(
            @PathVariable UUID id,
            Authentication authentication) {
        User user = getCurrentUser(authentication);
        
        return projectRepository.findById(id)
                .filter(p -> p.getOwner().getId().equals(user.getId()))
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }
    
    @PutMapping("/{id}")
    public ResponseEntity<Project> updateProject(
            @PathVariable UUID id,
            @RequestBody UpdateProjectRequest request,
            Authentication authentication) {
        User user = getCurrentUser(authentication);
        
        return projectRepository.findById(id)
                .filter(p -> p.getOwner().getId().equals(user.getId()))
                .map(project -> {
                    if (request.name() != null) {
                        project.setName(request.name());
                    }
                    if (request.description() != null) {
                        project.setDescription(request.description());
                    }
                    return ResponseEntity.ok(projectRepository.save(project));
                })
                .orElse(ResponseEntity.notFound().build());
    }
    
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteProject(
            @PathVariable UUID id,
            Authentication authentication) {
        User user = getCurrentUser(authentication);
        
        return projectRepository.findById(id)
                .filter(p -> p.getOwner().getId().equals(user.getId()))
                .map(project -> {
                    if (moduleRepository.existsByProjectIdAndOwnerIdAndLifecycleStateIn(id, user.getId(), java.util.List.of("RUNNING", "STARTING", "ERROR"))) {
                        return ResponseEntity.status(409).<Void>build();
                    }
                    projectRepository.delete(project);
                    return ResponseEntity.noContent().<Void>build();
                })
                .orElse(ResponseEntity.notFound().build());
    }
    
    record CreateProjectRequest(String name, String description) {}
    record UpdateProjectRequest(String name, String description) {}
}
