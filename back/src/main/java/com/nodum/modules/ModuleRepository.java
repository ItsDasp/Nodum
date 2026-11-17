package com.nodum.modules;

import com.nodum.projects.Project;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;
import java.util.Collection;
import java.util.Optional;

@Repository
public interface ModuleRepository extends JpaRepository<Module, UUID> {
    @EntityGraph(attributePaths = "project")
    Optional<Module> findWithProjectById(UUID id);
    List<Module> findByProjectId(UUID projectId);
    List<Module> findByProjectIdAndOwnerId(UUID projectId, UUID ownerId);
    List<Module> findByOwnerId(UUID ownerId);
    boolean existsByNameAndProjectId(String name, UUID projectId);
    boolean existsByProjectIdAndOwnerIdAndLifecycleStateIn(UUID projectId, UUID ownerId, Collection<String> lifecycleStates);
}
