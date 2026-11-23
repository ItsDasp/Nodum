package com.nodum.projects;

import com.nodum.identity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface ProjectRepository extends JpaRepository<Project, UUID> {
    List<Project> findByOwnerId(UUID ownerId);
    java.util.Optional<Project> findByNameAndOwnerId(String name, UUID ownerId);
}
