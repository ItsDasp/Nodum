package com.nodum.contracts;

import com.nodum.identity.User;
import com.nodum.identity.UserRepository;
import com.nodum.modules.Module;
import com.nodum.modules.ModuleRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;
import java.util.Optional;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/modules/{moduleId}/contracts")
public class ContractController {
    
    private final ContractRepository contractRepository;
    private final UserRepository userRepository;
    private final ModuleRepository moduleRepository;
    
    public ContractController(ContractRepository contractRepository, UserRepository userRepository, ModuleRepository moduleRepository) {
        this.contractRepository = contractRepository;
        this.userRepository = userRepository;
        this.moduleRepository = moduleRepository;
    }
    
    private User getCurrentUser(Authentication authentication) {
        String username = authentication.getName();
        return userRepository.findByUsername(username)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));
    }

    private Optional<Module> getOwnedModule(UUID projectId, UUID moduleId, User user) {
        return moduleRepository.findById(moduleId)
                .filter(module -> module.getProject().getId().equals(projectId))
                .filter(module -> module.getOwner().getId().equals(user.getId()));
    }
    
    @GetMapping
    public ResponseEntity<List<Contract>> getContracts(
            @PathVariable UUID projectId,
            @PathVariable UUID moduleId,
            Authentication authentication) {
        User user = getCurrentUser(authentication);
        if (getOwnedModule(projectId, moduleId, user).isEmpty()) return ResponseEntity.notFound().build();
        List<Contract> contracts = contractRepository.findByModuleIdAndOwnerId(moduleId, user.getId());
        return ResponseEntity.ok(contracts);
    }
    
    @GetMapping("/latest")
    public ResponseEntity<Contract> getLatestContract(
            @PathVariable UUID projectId,
            @PathVariable UUID moduleId,
            Authentication authentication) {
        User user = getCurrentUser(authentication);
        if (getOwnedModule(projectId, moduleId, user).isEmpty()) return ResponseEntity.notFound().build();
        return contractRepository.findByModuleIdAndOwnerId(moduleId, user.getId()).stream()
                .max(java.util.Comparator.comparing(Contract::getCreatedAt))
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }
    
    @PostMapping
    public ResponseEntity<Contract> createContract(
            @PathVariable UUID projectId,
            @PathVariable UUID moduleId,
            @RequestBody CreateContractRequest request,
            Authentication authentication) {
        User user = getCurrentUser(authentication);
        
        Module module = getOwnedModule(projectId, moduleId, user).orElse(null);
        if (module == null) return ResponseEntity.notFound().build();
        
        if (contractRepository.existsByNameAndModuleId(request.name(), moduleId)) {
            return ResponseEntity.badRequest().build();
        }
        
        Contract contract = new Contract();
        contract.setName(request.name());
        contract.setDescription(request.description());
        contract.setVersion(request.version());
        contract.setSchema(request.schema());
        contract.setExamples(request.examples());
        contract.setModule(module);
        contract.setOwner(user);
        
        Contract saved = contractRepository.save(contract);
        return ResponseEntity.ok(saved);
    }
    
    @PostMapping("/{id}/publish")
    public ResponseEntity<Contract> publishContract(
            @PathVariable UUID projectId,
            @PathVariable UUID moduleId,
            @PathVariable UUID id,
            Authentication authentication) {
        User user = getCurrentUser(authentication);
        
        return contractRepository.findById(id)
                .filter(c -> c.getModule().getId().equals(moduleId))
                .filter(c -> c.getModule().getProject().getId().equals(projectId))
                .filter(c -> c.getOwner().getId().equals(user.getId()))
                .map(contract -> {
                    contract.setStatus(ContractStatus.PUBLISHED);
                    return ResponseEntity.ok(contractRepository.save(contract));
                })
                .orElse(ResponseEntity.notFound().build());
    }
    
    @PostMapping("/{id}/new-version")
    public ResponseEntity<Contract> createNewVersion(
            @PathVariable UUID projectId,
            @PathVariable UUID moduleId,
            @PathVariable UUID id,
            @RequestBody NewVersionRequest request,
            Authentication authentication) {
        User user = getCurrentUser(authentication);
        
        return contractRepository.findById(id)
                .filter(c -> c.getModule().getId().equals(moduleId))
                .filter(c -> c.getModule().getProject().getId().equals(projectId))
                .filter(c -> c.getOwner().getId().equals(user.getId()))
                .map(contract -> {
                    Contract newContract = new Contract();
                    newContract.setName(contract.getName());
                    newContract.setDescription(request.description() != null ? request.description() : contract.getDescription());
                    newContract.setVersion(request.version());
                    newContract.setSchema(request.schema() != null ? request.schema() : contract.getSchema());
                    newContract.setExamples(request.examples() != null ? request.examples() : contract.getExamples());
                    newContract.setModule(contract.getModule());
                    newContract.setOwner(user);
                    newContract.setStatus(ContractStatus.DRAFT);
                    
                    return ResponseEntity.ok(contractRepository.save(newContract));
                })
                .orElse(ResponseEntity.notFound().build());
    }
    
    @GetMapping("/{id}")
    public ResponseEntity<Contract> getContract(
            @PathVariable UUID projectId,
            @PathVariable UUID moduleId,
            @PathVariable UUID id,
            Authentication authentication) {
        User user = getCurrentUser(authentication);
        
        return contractRepository.findById(id)
                .filter(c -> c.getModule().getId().equals(moduleId))
                .filter(c -> c.getModule().getProject().getId().equals(projectId))
                .filter(c -> c.getOwner().getId().equals(user.getId()))
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }
    
    @PutMapping("/{id}")
    public ResponseEntity<Contract> updateContract(
            @PathVariable UUID projectId,
            @PathVariable UUID moduleId,
            @PathVariable UUID id,
            @RequestBody UpdateContractRequest request,
            Authentication authentication) {
        User user = getCurrentUser(authentication);
        
        return contractRepository.findById(id)
                .filter(c -> c.getModule().getId().equals(moduleId))
                .filter(c -> c.getModule().getProject().getId().equals(projectId))
                .filter(c -> c.getOwner().getId().equals(user.getId()))
                .filter(c -> c.getStatus() == ContractStatus.DRAFT)
                .map(contract -> {
                    if (request.name() != null) {
                        contract.setName(request.name());
                    }
                    if (request.description() != null) {
                        contract.setDescription(request.description());
                    }
                    if (request.schema() != null) {
                        contract.setSchema(request.schema());
                    }
                    if (request.examples() != null) {
                        contract.setExamples(request.examples());
                    }
                    return ResponseEntity.ok(contractRepository.save(contract));
                })
                .orElse(ResponseEntity.notFound().build());
    }
    
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteContract(
            @PathVariable UUID projectId,
            @PathVariable UUID moduleId,
            @PathVariable UUID id,
            Authentication authentication) {
        User user = getCurrentUser(authentication);
        
        return contractRepository.findById(id)
                .filter(c -> c.getModule().getId().equals(moduleId))
                .filter(c -> c.getModule().getProject().getId().equals(projectId))
                .filter(c -> c.getOwner().getId().equals(user.getId()))
                .map(contract -> {
                    contractRepository.delete(contract);
                    return ResponseEntity.noContent().<Void>build();
                })
                .orElse(ResponseEntity.notFound().build());
    }
    
    record CreateContractRequest(String name, String description, String version, String schema, String examples) {}
    record UpdateContractRequest(String name, String description, String schema, String examples) {}
    record NewVersionRequest(String version, String description, String schema, String examples) {}
}
