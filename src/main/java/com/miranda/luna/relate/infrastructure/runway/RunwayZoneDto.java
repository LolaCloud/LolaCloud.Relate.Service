package com.miranda.luna.relate.infrastructure.runway;

public record RunwayZoneDto(
        String id,
        String name,
        String accountId,
        String accountName,
        String status,
        int recordsCount
) {
}
