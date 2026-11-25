package com.miranda.luna.relate.infrastructure.web.dto;

import java.util.Map;

public record RelateStatusResponse(
        String status,
        String service,
        String version,
        long totalInstances,
        long runningInstances,
        long stoppedInstances,
        Map<String, Long> engineBreakdown
) {
}
