package com.miranda.luna.relate.infrastructure.web.dto;

import java.time.Instant;

public record BackupDownloadResponse(
        String downloadUrl,
        String filename,
        Long sizeBytes,
        Instant expiresAt
) {
}
