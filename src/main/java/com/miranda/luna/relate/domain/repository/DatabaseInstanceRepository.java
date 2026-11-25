package com.miranda.luna.relate.domain.repository;

import com.miranda.luna.relate.domain.model.DatabaseInstance;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface DatabaseInstanceRepository extends JpaRepository<DatabaseInstance, UUID> {

    Optional<DatabaseInstance> findByName(String name);

    Optional<DatabaseInstance> findByHostSni(String hostSni);

    boolean existsByName(String name);

    boolean existsByHostSni(String hostSni);

    @Query("SELECT COALESCE(MAX(d.internalPort), 15431) FROM DatabaseInstance d")
    Integer findMaxInternalPort();
}
