package com.miranda.luna.relate.application.service;

import com.miranda.luna.relate.domain.exception.ConflictException;
import com.miranda.luna.relate.domain.exception.InvalidOperationException;
import com.miranda.luna.relate.domain.exception.ResourceNotFoundException;
import com.miranda.luna.relate.domain.model.DatabaseInstance;
import com.miranda.luna.relate.domain.model.EngineType;
import com.miranda.luna.relate.domain.model.InstanceStatus;
import com.miranda.luna.relate.domain.model.InstanceTier;
import com.miranda.luna.relate.domain.repository.DatabaseInstanceRepository;
import com.miranda.luna.relate.infrastructure.docker.DockerProvisionerService;
import com.miranda.luna.relate.infrastructure.runway.RunwayClientPort;
import com.miranda.luna.relate.infrastructure.runway.RunwayDnsRecordDto;
import com.miranda.luna.relate.infrastructure.runway.RunwayZoneDto;
import com.miranda.luna.relate.infrastructure.traefik.TraefikConfigService;
import com.miranda.luna.relate.infrastructure.web.dto.CreateInstanceRequest;
import com.miranda.luna.relate.infrastructure.web.dto.DatabaseInstanceResponse;
import com.miranda.luna.relate.infrastructure.web.dto.RelateStatusResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class InstanceServiceTest {

    @Mock
    private DatabaseInstanceRepository instanceRepository;

    @Mock
    private TraefikConfigService traefikConfigService;

    @Mock
    private DockerProvisionerService dockerProvisionerService;

    @Mock
    private RunwayClientPort runwayClient;

    private InstanceService instanceService;

    @BeforeEach
    void setUp() {
        instanceService = new InstanceService(instanceRepository, traefikConfigService, dockerProvisionerService, runwayClient, "db.lola-cloud.dev");
    }

    @Test
    void testCreatePostgresInstanceSuccess() {
        CreateInstanceRequest request = new CreateInstanceRequest(
                "my-postgres",
                EngineType.POSTGRESQL,
                "17",
                "pguser",
                "customPass123!",
                "custom_db",
                InstanceTier.STANDARD_1GB,
                20
        );

        when(instanceRepository.existsByName("my-postgres")).thenReturn(false);
        when(instanceRepository.existsByHostSni("my-postgres.db.lola-cloud.dev")).thenReturn(false);
        when(instanceRepository.findMaxInternalPort()).thenReturn(15431);
        when(dockerProvisionerService.provisionContainer(any(DatabaseInstance.class))).thenReturn("sim-pg-container");
        when(instanceRepository.save(any(DatabaseInstance.class))).thenAnswer(invocation -> invocation.getArgument(0));

        DatabaseInstanceResponse response = instanceService.createInstance(request);

        assertNotNull(response);
        assertEquals("my-postgres", response.name());
        assertEquals(EngineType.POSTGRESQL, response.engine());
        assertEquals("17", response.version());
        assertEquals(InstanceStatus.RUNNING, response.status());
        assertEquals("my-postgres.db.lola-cloud.dev", response.hostSni());
        assertEquals(5432, response.port());
        assertEquals(15432, response.internalPort());
        assertEquals("pguser", response.adminUsername());
        assertEquals("customPass123!", response.adminPassword());
        assertEquals("custom_db", response.defaultDatabase());
        assertEquals(InstanceTier.STANDARD_1GB, response.tier());
        assertEquals(20, response.storageGb());
        assertEquals("sim-pg-container", response.containerId());
        assertFalse(response.runwayIntegrated());
        assertTrue(response.connectionString().contains("sslmode=require"));

        verify(dockerProvisionerService).provisionContainer(any(DatabaseInstance.class));
        ArgumentCaptor<DatabaseInstance> captor = ArgumentCaptor.forClass(DatabaseInstance.class);
        verify(traefikConfigService).writeInstanceConfig(captor.capture());
        assertEquals("my-postgres", captor.getValue().getName());
        verify(runwayClient, never()).createDnsRecord(any(), any(), any(), any(), any(Boolean.class));
    }

    @Test
    void testCreateInstanceWithRunwayIntegrationAutoHostname() {
        UUID mockRecordId = UUID.randomUUID();
        CreateInstanceRequest request = new CreateInstanceRequest(
                "prod-pg",
                EngineType.POSTGRESQL,
                "17",
                "pgadmin",
                "Secr3t!",
                "proddb",
                InstanceTier.PRO_2GB,
                50,
                true,
                "zone-cf-100",
                "lola-cloud.com",
                null,
                true
        );

        when(instanceRepository.existsByName("prod-pg")).thenReturn(false);
        when(instanceRepository.existsByHostSni("prod-pg.lola-cloud.com")).thenReturn(false);
        when(instanceRepository.findMaxInternalPort()).thenReturn(15432);
        when(runwayClient.getCurrentWanIp(any())).thenReturn("198.51.100.25");
        when(runwayClient.createDnsRecord(any(), eq("zone-cf-100"), eq("prod-pg.lola-cloud.com"), eq("198.51.100.25"), eq(true)))
                .thenReturn(new RunwayDnsRecordDto(
                        mockRecordId,
                        null,
                        "zone-cf-100",
                        "lola-cloud.com",
                        "prod-pg.lola-cloud.com",
                        "A",
                        "198.51.100.25",
                        false,
                        1,
                        true,
                        "cf-rec-777",
                        Instant.now(),
                        Instant.now()
                ));
        when(dockerProvisionerService.provisionContainer(any(DatabaseInstance.class))).thenReturn("container-prod-pg");
        when(instanceRepository.save(any(DatabaseInstance.class))).thenAnswer(invocation -> invocation.getArgument(0));

        DatabaseInstanceResponse response = instanceService.createInstance(request);

        assertNotNull(response);
        assertEquals("prod-pg", response.name());
        assertEquals("prod-pg.lola-cloud.com", response.hostSni());
        assertTrue(response.runwayIntegrated());
        assertEquals(mockRecordId, response.runwayRecordId());
        assertEquals("lola-cloud.com", response.dnsZoneName());
        assertEquals("198.51.100.25", response.wanIp());
        assertTrue(response.ddnsEnabled());

        verify(runwayClient).getCurrentWanIp(any());
        verify(runwayClient).createDnsRecord(any(), eq("zone-cf-100"), eq("prod-pg.lola-cloud.com"), eq("198.51.100.25"), eq(true));

        ArgumentCaptor<DatabaseInstance> captor = ArgumentCaptor.forClass(DatabaseInstance.class);
        verify(traefikConfigService).writeInstanceConfig(captor.capture());
        assertEquals("prod-pg.lola-cloud.com", captor.getValue().getHostSni());
    }

    @Test
    void testCreateInstanceWithRunwayCustomHostname() {
        UUID mockRecordId = UUID.randomUUID();
        CreateInstanceRequest request = new CreateInstanceRequest(
                "tenant-mysql",
                EngineType.MYSQL,
                "8.0",
                null,
                null,
                null,
                null,
                null,
                true,
                "zone-custom-99",
                "custom-corp.org",
                "client1-db.custom-corp.org",
                false
        );

        when(instanceRepository.existsByName("tenant-mysql")).thenReturn(false);
        when(instanceRepository.existsByHostSni("client1-db.custom-corp.org")).thenReturn(false);
        when(instanceRepository.findMaxInternalPort()).thenReturn(15433);
        when(runwayClient.getCurrentWanIp(any())).thenReturn("198.51.100.50");
        when(runwayClient.createDnsRecord(any(), eq("zone-custom-99"), eq("client1-db.custom-corp.org"), eq("198.51.100.50"), eq(false)))
                .thenReturn(new RunwayDnsRecordDto(
                        mockRecordId,
                        null,
                        "zone-custom-99",
                        "custom-corp.org",
                        "client1-db.custom-corp.org",
                        "A",
                        "198.51.100.50",
                        false,
                        1,
                        false,
                        "cf-rec-888",
                        Instant.now(),
                        Instant.now()
                ));
        when(dockerProvisionerService.provisionContainer(any(DatabaseInstance.class))).thenReturn("container-mysql-custom");
        when(instanceRepository.save(any(DatabaseInstance.class))).thenAnswer(invocation -> invocation.getArgument(0));

        DatabaseInstanceResponse response = instanceService.createInstance(request);

        assertNotNull(response);
        assertEquals("tenant-mysql", response.name());
        assertEquals("client1-db.custom-corp.org", response.hostSni());
        assertTrue(response.runwayIntegrated());
        assertEquals(mockRecordId, response.runwayRecordId());
        assertFalse(response.ddnsEnabled());
    }

    @Test
    void testCreateInstanceRejectsMultiLevelSubdomain() {
        CreateInstanceRequest request = new CreateInstanceRequest(
                "tenant-mysql",
                EngineType.MYSQL,
                "8.0",
                null,
                null,
                null,
                null,
                null,
                true,
                "zone-custom-99",
                "custom-corp.org",
                "a.b.c.custom-corp.org",
                false
        );

        when(instanceRepository.existsByName("tenant-mysql")).thenReturn(false);

        assertThrows(InvalidOperationException.class, () -> instanceService.createInstance(request));
    }

    @Test
    void testDeleteInstanceWithRunwayIntegration() {
        UUID id = UUID.randomUUID();
        UUID recordId = UUID.randomUUID();
        DatabaseInstance instance = DatabaseInstance.builder()
                .id(id)
                .name("to-delete-runway")
                .containerId("c-del-runway")
                .runwayIntegrated(true)
                .runwayRecordId(recordId)
                .dnsZoneId("zone-1")
                .dnsZoneName("example.com")
                .build();

        when(instanceRepository.findById(id)).thenReturn(Optional.of(instance));

        instanceService.deleteInstance(id);

        verify(runwayClient).deleteDnsRecord(any(), eq(recordId));
        verify(dockerProvisionerService).deleteContainer("c-del-runway", "to-delete-runway");
        verify(traefikConfigService).deleteInstanceConfig("to-delete-runway");
        verify(instanceRepository).delete(instance);
    }

    @Test
    void testListRunwayZones() {
        when(runwayClient.listZones(any())).thenReturn(List.of(
                new RunwayZoneDto("z1", "domain1.com", "acc1", "Account 1", "active", 5),
                new RunwayZoneDto("z2", "domain2.com", "acc1", "Account 1", "active", 2)
        ));

        List<RunwayZoneDto> zones = instanceService.listRunwayZones();
        assertEquals(2, zones.size());
        assertEquals("domain1.com", zones.get(0).name());
        assertEquals("domain2.com", zones.get(1).name());
        verify(runwayClient).listZones(any());
    }

    @Test
    void testCreateSqlServerInstanceEncryptTrue() {
        CreateInstanceRequest request = new CreateInstanceRequest(
                "finance-db",
                EngineType.SQLSERVER,
                null,
                null,
                null,
                null,
                null,
                null
        );

        when(instanceRepository.existsByName("finance-db")).thenReturn(false);
        when(instanceRepository.existsByHostSni("finance-db.db.lola-cloud.dev")).thenReturn(false);
        when(instanceRepository.findMaxInternalPort()).thenReturn(15432);
        when(dockerProvisionerService.provisionContainer(any(DatabaseInstance.class))).thenReturn("sim-sql-container");
        when(instanceRepository.save(any(DatabaseInstance.class))).thenAnswer(invocation -> invocation.getArgument(0));

        DatabaseInstanceResponse response = instanceService.createInstance(request);

        assertNotNull(response);
        assertEquals(1433, response.port());
        assertEquals(15433, response.internalPort());
        assertEquals("sa", response.adminUsername());
        assertEquals("2022", response.version());
        assertEquals("finance_db", response.defaultDatabase());
        assertEquals("sim-sql-container", response.containerId());
        assertTrue(response.connectionString().contains("encrypt=true"));
        assertTrue(response.connectionString().contains("trustServerCertificate=false"));
        verify(dockerProvisionerService).provisionContainer(any(DatabaseInstance.class));
    }

    @Test
    void testCreateInstanceInvalidName() {
        CreateInstanceRequest request = new CreateInstanceRequest(
                "-invalid_name!",
                EngineType.POSTGRESQL,
                null, null, null, null, null, null
        );

        assertThrows(InvalidOperationException.class, () -> instanceService.createInstance(request));
    }

    @Test
    void testCreateInstanceNameConflict() {
        CreateInstanceRequest request = new CreateInstanceRequest(
                "existing-db",
                EngineType.POSTGRESQL,
                null, null, null, null, null, null
        );

        when(instanceRepository.existsByName("existing-db")).thenReturn(true);

        assertThrows(ConflictException.class, () -> instanceService.createInstance(request));
    }

    @Test
    void testStartStopRebootLifecycle() {
        UUID id = UUID.randomUUID();
        DatabaseInstance instance = DatabaseInstance.builder()
                .id(id)
                .name("lifecycle-db")
                .engine(EngineType.MYSQL)
                .version("8.0")
                .status(InstanceStatus.RUNNING)
                .hostSni("lifecycle-db.db.lola-cloud.dev")
                .port(3306)
                .internalPort(15435)
                .adminUsername("root")
                .adminPassword("pass")
                .defaultDatabase("lifecycle_db")
                .tier(InstanceTier.STANDARD_1GB)
                .storageGb(10)
                .connectionString("mysql://root:pass@lifecycle-db.db.lola-cloud.dev:3306/lifecycle_db?useSSL=true&requireSSL=true")
                .containerId("c-lifecycle-123")
                .build();

        when(instanceRepository.findById(id)).thenReturn(Optional.of(instance));
        when(instanceRepository.save(any(DatabaseInstance.class))).thenAnswer(invocation -> invocation.getArgument(0));

        // Stop
        DatabaseInstanceResponse stopped = instanceService.stopInstance(id);
        assertEquals(InstanceStatus.STOPPED, stopped.status());
        verify(dockerProvisionerService).stopContainer("c-lifecycle-123");
        verify(traefikConfigService).deleteInstanceConfig("lifecycle-db");

        // Start
        DatabaseInstanceResponse started = instanceService.startInstance(id);
        assertEquals(InstanceStatus.RUNNING, started.status());
        verify(dockerProvisionerService).startContainer("c-lifecycle-123");
        verify(traefikConfigService).writeInstanceConfig(instance);

        // Reboot
        DatabaseInstanceResponse rebooted = instanceService.rebootInstance(id);
        assertEquals(InstanceStatus.RUNNING, rebooted.status());
        verify(dockerProvisionerService).rebootContainer("c-lifecycle-123");
    }

    @Test
    void testDeleteInstance() {
        UUID id = UUID.randomUUID();
        DatabaseInstance instance = DatabaseInstance.builder()
                .id(id)
                .name("to-delete")
                .containerId("c-delete-123")
                .build();

        when(instanceRepository.findById(id)).thenReturn(Optional.of(instance));

        instanceService.deleteInstance(id);
        verify(dockerProvisionerService).deleteContainer("c-delete-123", "to-delete");
        verify(traefikConfigService).deleteInstanceConfig("to-delete");
        verify(instanceRepository).delete(instance);
    }

    @Test
    void testInstanceNotFound() {
        UUID id = UUID.randomUUID();
        when(instanceRepository.findById(id)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> instanceService.getInstanceById(id));
    }

    @Test
    void testGetStatusSummary() {
        DatabaseInstance inst1 = DatabaseInstance.builder()
                .name("db1").engine(EngineType.POSTGRESQL).status(InstanceStatus.RUNNING).build();
        DatabaseInstance inst2 = DatabaseInstance.builder()
                .name("db2").engine(EngineType.MYSQL).status(InstanceStatus.STOPPED).build();

        when(instanceRepository.findAll()).thenReturn(List.of(inst1, inst2));

        RelateStatusResponse summary = instanceService.getStatusSummary();
        assertEquals("UP", summary.status());
        assertEquals("relate", summary.service());
        assertEquals(2, summary.totalInstances());
        assertEquals(1, summary.runningInstances());
        assertEquals(1, summary.stoppedInstances());
        assertEquals(1L, summary.engineBreakdown().get("POSTGRESQL"));
        assertEquals(1L, summary.engineBreakdown().get("MYSQL"));
    }
}
