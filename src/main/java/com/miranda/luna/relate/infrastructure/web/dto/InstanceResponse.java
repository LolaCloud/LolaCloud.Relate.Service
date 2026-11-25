package com.miranda.luna.relate.infrastructure.web.dto;

import com.miranda.luna.relate.domain.model.DatabaseInstance;
import com.miranda.luna.relate.domain.model.EngineType;
import com.miranda.luna.relate.domain.model.InstanceStatus;
import com.miranda.luna.relate.domain.model.InstanceTier;

import java.time.Instant;
import java.util.UUID;

public record InstanceResponse(
        UUID id,
        String name,
        EngineType engine,
        String version,
        InstanceStatus status,
        String hostSni,
        Integer port,
        Integer internalPort,
        String adminUsername,
        String adminPassword,
        String defaultDatabase,
        InstanceTier tier,
        Integer storageGb,
        String connectionString,
        String containerId,
        Boolean runwayIntegrated,
        UUID runwayRecordId,
        String dnsZoneName,
        String wanIp,
        Boolean ddnsEnabled,
        Instant createdAt,
        Instant updatedAt
) {
    public static InstanceResponse from(DatabaseInstance entity) {
        return new InstanceResponse(
                entity.getId(),
                entity.getName(),
                entity.getEngine(),
                entity.getVersion(),
                entity.getStatus(),
                entity.getHostSni(),
                entity.getPort(),
                entity.getInternalPort(),
                entity.getAdminUsername(),
                entity.getAdminPassword(),
                entity.getDefaultDatabase(),
                entity.getTier(),
                entity.getStorageGb(),
                entity.getConnectionString(),
                entity.getContainerId(),
                entity.getRunwayIntegrated(),
                entity.getRunwayRecordId(),
                entity.getDnsZoneName(),
                entity.getWanIp(),
                entity.getDdnsEnabled(),
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }

    public static InstanceResponse from(DatabaseInstanceResponse r) {
        return new InstanceResponse(
                r.id(),
                r.name(),
                r.engine(),
                r.version(),
                r.status(),
                r.hostSni(),
                r.port(),
                r.internalPort(),
                r.adminUsername(),
                r.adminPassword(),
                r.defaultDatabase(),
                r.tier(),
                r.storageGb(),
                r.connectionString(),
                r.containerId(),
                r.runwayIntegrated(),
                r.runwayRecordId(),
                r.dnsZoneName(),
                r.wanIp(),
                r.ddnsEnabled(),
                r.createdAt(),
                r.updatedAt()
        );
    }
}
