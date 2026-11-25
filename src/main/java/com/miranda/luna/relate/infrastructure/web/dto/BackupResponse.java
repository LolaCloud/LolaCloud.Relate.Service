package com.miranda.luna.relate.infrastructure.web.dto;

import com.miranda.luna.relate.domain.model.DatabaseBackup;

import java.time.Instant;
import java.util.UUID;

public record BackupResponse(
        UUID id,
        String lrn,
        UUID instanceId,
        String instanceName,
        String name,
        String engine,
        Long sizeBytes,
        String status,
        String storagePath,
        String s3Bucket,
        String s3Key,
        String backupType,
        Instant createdAt,
        Instant completedAt,
        String errorMessage,
        String s3Prefix
) {
    public BackupResponse(
            UUID id,
            String lrn,
            UUID instanceId,
            String instanceName,
            String name,
            String engine,
            Long sizeBytes,
            String status,
            String storagePath,
            String s3Bucket,
            String s3Key,
            String backupType,
            Instant createdAt,
            Instant completedAt,
            String errorMessage
    ) {
        this(id, lrn, instanceId, instanceName, name, engine, sizeBytes, status, storagePath, s3Bucket, s3Key, backupType, createdAt, completedAt, errorMessage, null);
    }

    public static BackupResponse from(DatabaseBackup backup) {
        if (backup == null) {
            return null;
        }
        return new BackupResponse(
                backup.getId(),
                backup.getLrn(),
                backup.getInstanceId(),
                backup.getInstanceName(),
                backup.getName(),
                backup.getEngine(),
                backup.getSizeBytes(),
                backup.getStatus(),
                backup.getStoragePath(),
                backup.getS3Bucket(),
                backup.getS3Key(),
                backup.getBackupType(),
                backup.getCreatedAt(),
                backup.getCompletedAt(),
                backup.getErrorMessage(),
                backup.getS3Prefix()
        );
    }
}
