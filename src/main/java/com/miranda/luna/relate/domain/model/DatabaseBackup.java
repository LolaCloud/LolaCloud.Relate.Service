package com.miranda.luna.relate.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "database_backups")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DatabaseBackup {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "instance_id", nullable = false)
    private UUID instanceId;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Column(name = "size_bytes")
    private Long sizeBytes;

    @Column(name = "status", nullable = false, length = 50)
    private String status;

    @Column(name = "storage_path", columnDefinition = "TEXT")
    private String storagePath;

    @Column(name = "instance_name", length = 100)
    private String instanceName;

    @Column(name = "engine", length = 50)
    private String engine;

    @Column(name = "s3_bucket", length = 63)
    @Builder.Default
    private String s3Bucket = "lola-relate-backups";

    @Column(name = "s3_prefix", length = 255)
    private String s3Prefix;

    @Column(name = "s3_key", columnDefinition = "TEXT")
    private String s3Key;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "backup_type", length = 50)
    @Builder.Default
    private String backupType = "MANUAL_SNAPSHOT";

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    public void prePersist() {
        if (id == null) {
            id = UUID.randomUUID();
        }
        if (createdAt == null) {
            createdAt = Instant.now();
        }
        if (status == null) {
            status = "PENDING";
        }
        if (s3Bucket == null) {
            s3Bucket = "lola-relate-backups";
        }
        if (backupType == null) {
            backupType = "MANUAL_SNAPSHOT";
        }
    }

    public String getLrn() {
        return "lola:relate:backup:" + (id != null ? id.toString() : name);
    }
}
