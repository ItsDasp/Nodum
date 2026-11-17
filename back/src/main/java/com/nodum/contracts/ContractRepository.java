package com.nodum.contracts;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ContractRepository extends JpaRepository<Contract, UUID> {
    List<Contract> findByModuleId(UUID moduleId);
    List<Contract> findByModuleIdAndOwnerId(UUID moduleId, UUID ownerId);
    List<Contract> findByOwnerId(UUID ownerId);
    Optional<Contract> findFirstByModuleIdOrderByVersionDesc(UUID moduleId);
    boolean existsByNameAndModuleId(String name, UUID moduleId);
}
