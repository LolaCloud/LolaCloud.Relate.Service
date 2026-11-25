package com.miranda.luna.relate.infrastructure.web.dto;

import com.miranda.luna.relate.domain.model.EngineType;
import com.miranda.luna.relate.domain.model.InstanceTier;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.util.UUID;

public record CreateInstanceRequest(
        @NotBlank(message = "Instance name is required")
        @Pattern(regexp = "^[a-zA-Z0-9]([a-zA-Z0-9-]*[a-zA-Z0-9])?$", message = "Name must be alphanumeric with hyphens")
        String name,

        @NotNull(message = "Engine type is required")
        EngineType engine,

        String version,
        String adminUsername,
        String adminPassword,
        String defaultDatabase,
        InstanceTier tier,
        Integer storageGb,

        Boolean enableRunwayIntegration,
        String zoneId,
        String zoneName,
        String hostname,
        Boolean enableDdns,
        UUID restoreFromBackupId
) {
    public CreateInstanceRequest(
            String name,
            EngineType engine,
            String version,
            String adminUsername,
            String adminPassword,
            String defaultDatabase,
            InstanceTier tier,
            Integer storageGb
    ) {
        this(name, engine, version, adminUsername, adminPassword, defaultDatabase, tier, storageGb, false, null, null, null, true, null);
    }

    public CreateInstanceRequest(
            String name,
            EngineType engine,
            String version,
            String adminUsername,
            String adminPassword,
            String defaultDatabase,
            InstanceTier tier,
            Integer storageGb,
            Boolean enableRunwayIntegration,
            String zoneId,
            String zoneName,
            String hostname,
            Boolean enableDdns
    ) {
        this(name, engine, version, adminUsername, adminPassword, defaultDatabase, tier, storageGb, enableRunwayIntegration, zoneId, zoneName, hostname, enableDdns, null);
    }
}
