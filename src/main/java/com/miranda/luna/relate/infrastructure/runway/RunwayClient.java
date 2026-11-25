package com.miranda.luna.relate.infrastructure.runway;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
public class RunwayClient implements RunwayClientPort {

    private static final Logger logger = LoggerFactory.getLogger(RunwayClient.class);
    private static final String DEFAULT_FALLBACK_WAN_IP = "127.0.0.1";

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String baseUrl;

    @org.springframework.beans.factory.annotation.Autowired
    public RunwayClient(
            ObjectMapper objectMapper,
            @Value("${lola.runway.api.base-url:http://localhost:8083}") String baseUrl
    ) {
        this(
                HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(3))
                        .build(),
                objectMapper,
                baseUrl
        );
    }

    public RunwayClient(
            HttpClient httpClient,
            ObjectMapper objectMapper,
            String baseUrl
    ) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.baseUrl = baseUrl != null && baseUrl.endsWith("/")
                ? baseUrl.substring(0, baseUrl.length() - 1)
                : baseUrl;
    }

    @Override
    public String getCurrentWanIp(String bearerToken) {
        try {
            HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/api/v1/runway/edge-ip"))
                    .timeout(Duration.ofSeconds(3))
                    .header("Accept", "application/json")
                    .GET();

            if (bearerToken != null && !bearerToken.isBlank()) {
                reqBuilder.header("Authorization", "Bearer " + bearerToken.trim());
            }

            HttpResponse<String> response = httpClient.send(reqBuilder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                JsonNode root = objectMapper.readTree(response.body());
                JsonNode dataNode = root.has("data") ? root.get("data") : root;

                String wanIp = null;
                if (dataNode.hasNonNull("wanIp")) {
                    wanIp = dataNode.get("wanIp").asText().trim();
                } else if (dataNode.hasNonNull("currentWanIp")) {
                    wanIp = dataNode.get("currentWanIp").asText().trim();
                }

                if (wanIp != null && !wanIp.isBlank()) {
                    logger.info("Retrieved WAN IP '{}' from Runway edge-ip", wanIp);
                    return wanIp;
                }
            } else {
                logger.warn("Runway edge-ip endpoint returned HTTP {}", response.statusCode());
            }
        } catch (Exception e) {
            logger.warn("Failed retrieving WAN IP from Runway ({}): {}", baseUrl, e.getMessage());
        }

        logger.info("Using fallback WAN IP '{}'", DEFAULT_FALLBACK_WAN_IP);
        return DEFAULT_FALLBACK_WAN_IP;
    }

    @Override
    public List<RunwayZoneDto> listZones(String bearerToken) {
        try {
            HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/api/v1/runway/zones"))
                    .timeout(Duration.ofSeconds(3))
                    .header("Accept", "application/json")
                    .GET();

            if (bearerToken != null && !bearerToken.isBlank()) {
                reqBuilder.header("Authorization", "Bearer " + bearerToken.trim());
            }

            HttpResponse<String> response = httpClient.send(reqBuilder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                JsonNode root = objectMapper.readTree(response.body());
                JsonNode arrayNode = root.has("data") && root.get("data").isArray()
                        ? root.get("data")
                        : (root.isArray() ? root : null);

                if (arrayNode != null) {
                    List<RunwayZoneDto> zones = new ArrayList<>();
                    for (JsonNode item : arrayNode) {
                        zones.add(new RunwayZoneDto(
                                item.path("id").asText(),
                                item.path("name").asText(),
                                item.hasNonNull("accountId") ? item.get("accountId").asText() : null,
                                item.hasNonNull("accountName") ? item.get("accountName").asText() : null,
                                item.path("status").asText("active"),
                                item.path("recordsCount").asInt(0)
                        ));
                    }
                    return zones;
                }
            } else {
                logger.warn("Runway list zones endpoint returned HTTP {}", response.statusCode());
            }
        } catch (Exception e) {
            logger.warn("Failed retrieving DNS zones from Runway ({}): {}", baseUrl, e.getMessage());
        }

        return Collections.emptyList();
    }

    @Override
    public RunwayDnsRecordDto createDnsRecord(String bearerToken, String zoneId, String name, String content, boolean autoSync) {
        Map<String, Object> requestBody = new LinkedHashMap<>();
        requestBody.put("zoneId", zoneId != null ? zoneId : "");
        requestBody.put("type", "A");
        requestBody.put("name", name);
        requestBody.put("content", content);
        requestBody.put("proxied", false);
        requestBody.put("ttl", 1);
        requestBody.put("autoSync", autoSync);

        try {
            String jsonPayload = objectMapper.writeValueAsString(requestBody);
            HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/api/v1/runway/records"))
                    .timeout(Duration.ofSeconds(4))
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonPayload, StandardCharsets.UTF_8));

            if (bearerToken != null && !bearerToken.isBlank()) {
                reqBuilder.header("Authorization", "Bearer " + bearerToken.trim());
            }

            HttpResponse<String> response = httpClient.send(reqBuilder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                JsonNode root = objectMapper.readTree(response.body());
                JsonNode dataNode = root.has("data") ? root.get("data") : root;

                UUID id = dataNode.hasNonNull("id") ? UUID.fromString(dataNode.get("id").asText()) : UUID.randomUUID();
                UUID accountId = dataNode.hasNonNull("accountId") ? UUID.fromString(dataNode.get("accountId").asText()) : null;
                String rZoneId = dataNode.path("zoneId").asText(zoneId);
                String rZoneName = dataNode.hasNonNull("zoneName") ? dataNode.get("zoneName").asText() : null;
                String rName = dataNode.path("name").asText(name);
                String rType = dataNode.path("type").asText("A");
                String rContent = dataNode.path("content").asText(content);
                boolean rProxied = dataNode.path("proxied").asBoolean(false);
                int rTtl = dataNode.path("ttl").asInt(1);
                boolean rAutoSync = dataNode.path("autoSync").asBoolean(autoSync);
                String cfRecordId = dataNode.hasNonNull("cfRecordId") ? dataNode.get("cfRecordId").asText() : null;
                Instant lastSyncedAt = parseInstant(dataNode.path("lastSyncedAt").asText(null));
                Instant createdAt = parseInstant(dataNode.path("createdAt").asText(null));

                logger.info("Successfully created DNS A record via Runway: id={}, name={}, content={}, proxied={}",
                        id, rName, rContent, rProxied);

                return new RunwayDnsRecordDto(
                        id, accountId, rZoneId, rZoneName, rName, rType, rContent,
                        rProxied, rTtl, rAutoSync, cfRecordId, lastSyncedAt,
                        createdAt != null ? createdAt : Instant.now()
                );
            } else {
                logger.warn("Runway create record endpoint returned HTTP {}: {}", response.statusCode(), response.body());
            }
        } catch (Exception e) {
            logger.warn("Failed creating DNS record via Runway ({}): {}", name, e.getMessage());
        }

        // Graceful offline fallback
        logger.warn("Runway is offline or unreachable. Generating simulated DNS record for {} (offline fallback)", name);
        UUID fallbackId = UUID.randomUUID();
        return new RunwayDnsRecordDto(
                fallbackId,
                null,
                zoneId,
                null,
                name,
                "A",
                content,
                false,
                1,
                autoSync,
                "sim-offline-" + fallbackId,
                Instant.now(),
                Instant.now()
        );
    }

    @Override
    public void deleteDnsRecord(String bearerToken, UUID recordId) {
        if (recordId == null) {
            return;
        }

        try {
            HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/api/v1/runway/records/" + recordId))
                    .timeout(Duration.ofSeconds(3))
                    .header("Accept", "application/json")
                    .DELETE();

            if (bearerToken != null && !bearerToken.isBlank()) {
                reqBuilder.header("Authorization", "Bearer " + bearerToken.trim());
            }

            HttpResponse<String> response = httpClient.send(reqBuilder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                logger.info("Successfully deleted DNS record {} via Runway", recordId);
            } else if (response.statusCode() == 404) {
                logger.debug("DNS record {} not found in Runway during deletion (already removed)", recordId);
            } else {
                logger.warn("Runway delete record endpoint returned HTTP {} for id {}", response.statusCode(), recordId);
            }
        } catch (Exception e) {
            logger.warn("Failed communicating with Runway to delete DNS record {}: {}", recordId, e.getMessage());
        }
    }

    @Override
    public void deleteDnsRecordsByHostname(String bearerToken, String zoneId, String hostname) {
        if (hostname == null || hostname.isBlank()) {
            return;
        }
        try {
            String uri = baseUrl + "/api/v1/runway/records" + (zoneId != null && !zoneId.isBlank() ? "?zoneId=" + zoneId : "");
            HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(uri))
                    .timeout(Duration.ofSeconds(4))
                    .header("Accept", "application/json")
                    .GET();

            if (bearerToken != null && !bearerToken.isBlank()) {
                reqBuilder.header("Authorization", "Bearer " + bearerToken.trim());
            }

            HttpResponse<String> response = httpClient.send(reqBuilder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                JsonNode root = objectMapper.readTree(response.body());
                JsonNode array = root.has("data") && root.get("data").isArray() ? root.get("data") : (root.isArray() ? root : null);
                if (array != null) {
                    for (JsonNode r : array) {
                        String rName = r.path("name").asText("");
                        if (hostname.equalsIgnoreCase(rName)) {
                            String rIdStr = r.path("id").asText();
                            if (!rIdStr.isBlank()) {
                                deleteDnsRecord(bearerToken, UUID.fromString(rIdStr));
                                logger.info("Deleted matching DNS record {} ({}) during instance cleanup", rIdStr, hostname);
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            logger.warn("Failed checking/deleting matching DNS records for {}: {}", hostname, e.getMessage());
        }
    }

    private Instant parseInstant(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(text);
        } catch (Exception e) {
            return null;
        }
    }
}
