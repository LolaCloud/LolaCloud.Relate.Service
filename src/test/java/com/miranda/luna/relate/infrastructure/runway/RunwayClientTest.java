package com.miranda.luna.relate.infrastructure.runway;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RunwayClientTest {

    @Mock
    private HttpClient httpClient;

    @Mock
    private HttpResponse<String> httpResponse;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private RunwayClient runwayClient;

    @BeforeEach
    void setUp() {
        runwayClient = new RunwayClient(httpClient, objectMapper, "http://localhost:8083");
    }

    @Test
    void testGetCurrentWanIpSuccessWithCurrentWanIpField() throws Exception {
        String json = """
                {
                    "success": true,
                    "data": {
                        "currentWanIp": "203.0.113.195",
                        "lastCheckedAt": "2026-09-21T00:00:00Z",
                        "autoFixEnabled": true,
                        "syncStatus": "SYNCHRONIZED"
                    },
                    "requestId": "req-123"
                }
                """;

        when(httpResponse.statusCode()).thenReturn(200);
        when(httpResponse.body()).thenReturn(json);
        doReturn(httpResponse).when(httpClient).send(any(HttpRequest.class), any());

        String ip = runwayClient.getCurrentWanIp("test-token");
        assertEquals("203.0.113.195", ip);

        ArgumentCaptor<HttpRequest> requestCaptor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(httpClient).send(requestCaptor.capture(), any());
        HttpRequest req = requestCaptor.getValue();
        assertEquals("http://localhost:8083/api/v1/runway/edge-ip", req.uri().toString());
        assertEquals("GET", req.method());
        assertTrue(req.headers().firstValue("Authorization").orElse("").contains("Bearer test-token"));
    }

    @Test
    void testGetCurrentWanIpSuccessWithWanIpField() throws Exception {
        String json = """
                {
                    "success": true,
                    "data": {
                        "wanIp": "198.51.100.42"
                    }
                }
                """;

        when(httpResponse.statusCode()).thenReturn(200);
        when(httpResponse.body()).thenReturn(json);
        doReturn(httpResponse).when(httpClient).send(any(HttpRequest.class), any());

        String ip = runwayClient.getCurrentWanIp(null);
        assertEquals("198.51.100.42", ip);
    }

    @Test
    void testGetCurrentWanIpFallbackOnHttpError() throws Exception {
        when(httpResponse.statusCode()).thenReturn(500);
        doReturn(httpResponse).when(httpClient).send(any(HttpRequest.class), any());

        String ip = runwayClient.getCurrentWanIp("test-token");
        assertEquals("127.0.0.1", ip);
    }

    @Test
    void testGetCurrentWanIpFallbackOnException() throws Exception {
        doThrow(new IOException("Connection refused")).when(httpClient).send(any(HttpRequest.class), any());

        String ip = runwayClient.getCurrentWanIp("test-token");
        assertEquals("127.0.0.1", ip);
    }

    @Test
    void testListZonesSuccess() throws Exception {
        String json = """
                {
                    "success": true,
                    "data": [
                        {
                            "id": "zone-cf-1",
                            "name": "example.com",
                            "accountId": "acc-1",
                            "accountName": "Prod Account",
                            "status": "active",
                            "recordsCount": 12
                        },
                        {
                            "id": "zone-cf-2",
                            "name": "staging.net",
                            "accountId": "acc-1",
                            "accountName": "Prod Account",
                            "status": "active",
                            "recordsCount": 4
                        }
                    ]
                }
                """;

        when(httpResponse.statusCode()).thenReturn(200);
        when(httpResponse.body()).thenReturn(json);
        doReturn(httpResponse).when(httpClient).send(any(HttpRequest.class), any());

        List<RunwayZoneDto> zones = runwayClient.listZones("valid-token");
        assertEquals(2, zones.size());
        assertEquals("zone-cf-1", zones.get(0).id());
        assertEquals("example.com", zones.get(0).name());
        assertEquals("Prod Account", zones.get(0).accountName());
        assertEquals(12, zones.get(0).recordsCount());
        assertEquals("zone-cf-2", zones.get(1).id());
        assertEquals("staging.net", zones.get(1).name());
    }

    @Test
    void testListZonesFallbackOnException() throws Exception {
        doThrow(new IOException("Network timeout")).when(httpClient).send(any(HttpRequest.class), any());

        List<RunwayZoneDto> zones = runwayClient.listZones("token");
        assertNotNull(zones);
        assertTrue(zones.isEmpty());
    }

    @Test
    void testCreateDnsRecordSuccess() throws Exception {
        UUID recordId = UUID.randomUUID();
        String json = """
                {
                    "success": true,
                    "data": {
                        "id": "%s",
                        "zoneId": "zone-cf-1",
                        "name": "mydb.example.com",
                        "type": "A",
                        "content": "203.0.113.195",
                        "proxied": false,
                        "ttl": 1,
                        "autoSync": true,
                        "cfRecordId": "cf-rec-123",
                        "createdAt": "2026-09-21T00:00:00Z"
                    }
                }
                """.formatted(recordId);

        when(httpResponse.statusCode()).thenReturn(201);
        when(httpResponse.body()).thenReturn(json);
        doReturn(httpResponse).when(httpClient).send(any(HttpRequest.class), any());

        RunwayDnsRecordDto record = runwayClient.createDnsRecord(
                "bearer-token",
                "zone-cf-1",
                "mydb.example.com",
                "203.0.113.195",
                true
        );

        assertNotNull(record);
        assertEquals(recordId, record.id());
        assertEquals("zone-cf-1", record.zoneId());
        assertEquals("mydb.example.com", record.name());
        assertEquals("203.0.113.195", record.content());
        assertFalse(record.proxied(), "proxied MUST be false for raw TCP SNI routing");
        assertEquals(1, record.ttl());
        assertTrue(record.autoSync());
        assertEquals("cf-rec-123", record.cfRecordId());

        ArgumentCaptor<HttpRequest> requestCaptor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(httpClient).send(requestCaptor.capture(), any());
        HttpRequest req = requestCaptor.getValue();
        assertEquals("POST", req.method());
        assertEquals("http://localhost:8083/api/v1/runway/records", req.uri().toString());
    }

    @Test
    void testCreateDnsRecordOfflineFallback() throws Exception {
        doThrow(new IOException("ConnectException: Connection refused")).when(httpClient).send(any(HttpRequest.class), any());

        RunwayDnsRecordDto record = runwayClient.createDnsRecord(
                "token",
                "zone-123",
                "offline-db.example.com",
                "127.0.0.1",
                true
        );

        assertNotNull(record);
        assertNotNull(record.id());
        assertEquals("zone-123", record.zoneId());
        assertEquals("offline-db.example.com", record.name());
        assertEquals("127.0.0.1", record.content());
        assertFalse(record.proxied());
        assertTrue(record.autoSync());
        assertTrue(record.cfRecordId().startsWith("sim-offline-"));
    }

    @Test
    void testDeleteDnsRecordSuccess() throws Exception {
        UUID recordId = UUID.randomUUID();
        when(httpResponse.statusCode()).thenReturn(200);
        doReturn(httpResponse).when(httpClient).send(any(HttpRequest.class), any());

        runwayClient.deleteDnsRecord("token", recordId);

        ArgumentCaptor<HttpRequest> requestCaptor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(httpClient).send(requestCaptor.capture(), any());
        HttpRequest req = requestCaptor.getValue();
        assertEquals("DELETE", req.method());
        assertEquals("http://localhost:8083/api/v1/runway/records/" + recordId, req.uri().toString());
    }

    @Test
    void testDeleteDnsRecordGracefulOnException() throws Exception {
        UUID recordId = UUID.randomUUID();
        doThrow(new IOException("Connection reset")).when(httpClient).send(any(HttpRequest.class), any());

        // Must not throw
        runwayClient.deleteDnsRecord("token", recordId);
    }
}
