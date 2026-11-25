package com.miranda.luna.relate.infrastructure.runway;

import java.util.List;
import java.util.UUID;

public interface RunwayClientPort {

    String getCurrentWanIp(String bearerToken);

    List<RunwayZoneDto> listZones(String bearerToken);

    RunwayDnsRecordDto createDnsRecord(String bearerToken, String zoneId, String name, String content, boolean autoSync);

    void deleteDnsRecord(String bearerToken, UUID recordId);

    void deleteDnsRecordsByHostname(String bearerToken, String zoneId, String hostname);
}
