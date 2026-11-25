package com.miranda.luna.relate.infrastructure.runway;

import java.time.Instant;
import java.util.UUID;

public record RunwayDnsRecordDto(
        UUID id,
        UUID accountId,
        String zoneId,
        String zoneName,
        String name,
        String type,
        String content,
        boolean proxied,
        int ttl,
        boolean autoSync,
        String cfRecordId,
        Instant lastSyncedAt,
        Instant createdAt
) {
}
