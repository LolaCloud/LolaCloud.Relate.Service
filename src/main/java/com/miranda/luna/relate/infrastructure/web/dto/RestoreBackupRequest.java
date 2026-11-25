package com.miranda.luna.relate.infrastructure.web.dto;

import java.util.UUID;

public record RestoreBackupRequest(
        UUID targetInstanceId
) {
}
