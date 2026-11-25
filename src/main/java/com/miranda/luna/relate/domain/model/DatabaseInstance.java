package com.miranda.luna.relate.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "database_instances")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DatabaseInstance {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "name", nullable = false, unique = true, length = 100)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "engine", nullable = false, length = 50)
    private EngineType engine;

    @Column(name = "version", nullable = false, length = 50)
    private String version;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 50)
    private InstanceStatus status;

    @Column(name = "host_sni", nullable = false, unique = true, length = 255)
    private String hostSni;

    @Column(name = "port", nullable = false)
    private Integer port;

    @Column(name = "internal_port", nullable = false, unique = true)
    private Integer internalPort;

    @Column(name = "admin_username", nullable = false, length = 100)
    private String adminUsername;

    @Column(name = "admin_password", nullable = false, length = 255)
    private String adminPassword;

    @Column(name = "default_database", nullable = false, length = 100)
    private String defaultDatabase;

    @Enumerated(EnumType.STRING)
    @Column(name = "tier", nullable = false, length = 50)
    private InstanceTier tier;

    @Column(name = "storage_gb", nullable = false)
    @Builder.Default
    private Integer storageGb = 10;

    @Column(name = "connection_string", nullable = false, columnDefinition = "TEXT")
    private String connectionString;

    @Column(name = "container_id", length = 255)
    private String containerId;

    @Column(name = "runway_integrated", nullable = false)
    @Builder.Default
    private Boolean runwayIntegrated = false;

    @Column(name = "runway_record_id")
    private UUID runwayRecordId;

    @Column(name = "dns_zone_id", length = 100)
    private String dnsZoneId;

    @Column(name = "dns_zone_name", length = 100)
    private String dnsZoneName;

    @Column(name = "wan_ip", length = 50)
    private String wanIp;

    @Column(name = "ddns_enabled", nullable = false)
    @Builder.Default
    private Boolean ddnsEnabled = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    public void prePersist() {
        if (id == null) {
            id = UUID.randomUUID();
        }
        Instant now = Instant.now();
        if (createdAt == null) {
            createdAt = now;
        }
        if (updatedAt == null) {
            updatedAt = now;
        }
        if (storageGb == null) {
            storageGb = 10;
        }
        if (runwayIntegrated == null) {
            runwayIntegrated = false;
        }
        if (ddnsEnabled == null) {
            ddnsEnabled = true;
        }
    }

    @PreUpdate
    public void preUpdate() {
        updatedAt = Instant.now();
    }

    public String getLrn() {
        return "lola:relate:instance:" + (id != null ? id.toString() : name);
    }
}
