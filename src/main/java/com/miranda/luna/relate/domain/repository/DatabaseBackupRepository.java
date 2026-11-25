package com.miranda.luna.relate.domain.repository;

import com.miranda.luna.relate.domain.model.DatabaseBackup;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface DatabaseBackupRepository extends JpaRepository<DatabaseBackup, UUID> {

    List<DatabaseBackup> findByInstanceId(UUID instanceId);
    List<DatabaseBackup> findByInstanceIdOrderByCreatedAtDesc(UUID instanceId);
    List<DatabaseBackup> findAllByOrderByCreatedAtDesc();
}
