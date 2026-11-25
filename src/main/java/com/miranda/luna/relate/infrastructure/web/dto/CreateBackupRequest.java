package com.miranda.luna.relate.infrastructure.web.dto;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record CreateBackupRequest(
        @NotNull(message = "Instance ID is required")
        UUID instanceId,
        String name
) {
}
