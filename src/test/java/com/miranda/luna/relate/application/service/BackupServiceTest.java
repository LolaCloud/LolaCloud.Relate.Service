package com.miranda.luna.relate.application.service;

import com.miranda.luna.relate.domain.exception.InvalidOperationException;
import com.miranda.luna.relate.domain.exception.ResourceNotFoundException;
import com.miranda.luna.relate.domain.model.DatabaseBackup;
import com.miranda.luna.relate.domain.model.DatabaseInstance;
import com.miranda.luna.relate.domain.model.EngineType;
import com.miranda.luna.relate.domain.model.InstanceStatus;
import com.miranda.luna.relate.domain.model.InstanceTier;
import com.miranda.luna.relate.domain.repository.DatabaseBackupRepository;
import com.miranda.luna.relate.domain.repository.DatabaseInstanceRepository;
import com.miranda.luna.relate.infrastructure.docker.DockerProvisionerService;
import com.miranda.luna.relate.infrastructure.storage.RelateStorageService;
import com.miranda.luna.relate.infrastructure.web.dto.BackupDownloadResponse;
import com.miranda.luna.relate.infrastructure.web.dto.BackupResponse;
import com.miranda.luna.relate.infrastructure.web.dto.CreateBackupRequest;
import com.miranda.luna.relate.infrastructure.web.dto.RestoreBackupRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BackupServiceTest {

    private DatabaseBackupRepository backupRepository;
    private DatabaseInstanceRepository instanceRepository;
    private DockerProvisionerService dockerProvisionerService;
    private RelateStorageService storageService;
    private BackupService backupService;

    @BeforeEach
    void setUp() {
        backupRepository = mock(DatabaseBackupRepository.class);
        instanceRepository = mock(DatabaseInstanceRepository.class);
        dockerProvisionerService = mock(DockerProvisionerService.class);
        storageService = mock(RelateStorageService.class);

        when(storageService.getBucketName()).thenReturn("lola-relate-backups");

        backupService = new BackupService(
                backupRepository,
                instanceRepository,
                dockerProvisionerService,
                storageService
        );
    }

    private DatabaseInstance createTestInstance(UUID id, String name, EngineType engine) {
        return DatabaseInstance.builder()
                .id(id)
                .name(name)
                .engine(engine)
                .version(engine.getDefaultVersion())
                .status(InstanceStatus.RUNNING)
                .hostSni(name + ".db.lola-cloud.dev")
                .port(engine.getDefaultPort())
                .internalPort(15432)
                .adminUsername("admin")
                .adminPassword("secretPass123")
                .defaultDatabase("test_db")
                .tier(InstanceTier.STANDARD_1GB)
                .storageGb(10)
                .connectionString("dummy-url")
                .containerId("c-" + name)
                .build();
    }

    @Test
    void testCreatePostgresBackupSimulationMode() {
        UUID instanceId = UUID.randomUUID();
        DatabaseInstance instance = createTestInstance(instanceId, "prod-pg", EngineType.POSTGRESQL);

        when(instanceRepository.findById(instanceId)).thenReturn(Optional.of(instance));
        when(dockerProvisionerService.isEnabled()).thenReturn(false);
        when(backupRepository.save(any(DatabaseBackup.class))).thenAnswer(inv -> inv.getArgument(0));

        CreateBackupRequest request = new CreateBackupRequest(instanceId, "nightly-pg-backup");
        BackupResponse response = backupService.createBackup(request);

        assertNotNull(response);
        assertEquals("nightly-pg-backup", response.name());
        assertEquals(instanceId, response.instanceId());
        assertEquals("prod-pg", response.instanceName());
        assertEquals("POSTGRESQL", response.engine());
        assertEquals("COMPLETED", response.status());
        assertEquals("lola-relate-backups", response.s3Bucket());
        assertTrue(response.s3Key().endsWith(".dump"));
        assertTrue(response.s3Key().startsWith("instances/" + instanceId + "/backups/"));
        assertEquals("s3://lola-relate-backups/" + response.s3Key(), response.storagePath());
        assertNotNull(response.completedAt());

        verify(storageService, times(1)).ensureBackupBucketExists();
        verify(storageService, times(1)).uploadSnapshot(eq(response.s3Key()), any(byte[].class), eq("application/octet-stream"));
    }

    @Test
    void testCreateMysqlBackupWithGeneratedName() {
        UUID instanceId = UUID.randomUUID();
        DatabaseInstance instance = createTestInstance(instanceId, "app-mysql", EngineType.MYSQL);

        when(instanceRepository.findById(instanceId)).thenReturn(Optional.of(instance));
        when(dockerProvisionerService.isEnabled()).thenReturn(false);
        when(backupRepository.save(any(DatabaseBackup.class))).thenAnswer(inv -> inv.getArgument(0));

        CreateBackupRequest request = new CreateBackupRequest(instanceId, null);
        BackupResponse response = backupService.createBackup(request);

        assertNotNull(response);
        assertTrue(response.name().startsWith("snapshot-app-mysql-"));
        assertEquals("MYSQL", response.engine());
        assertTrue(response.s3Key().endsWith(".sql.gz"));
        assertEquals("COMPLETED", response.status());

        verify(storageService, times(1)).uploadSnapshot(eq(response.s3Key()), any(byte[].class), eq("application/gzip"));
    }

    @Test
    void testCreateSqlServerBackup() {
        UUID instanceId = UUID.randomUUID();
        DatabaseInstance instance = createTestInstance(instanceId, "erp-mssql", EngineType.SQLSERVER);

        when(instanceRepository.findById(instanceId)).thenReturn(Optional.of(instance));
        when(dockerProvisionerService.isEnabled()).thenReturn(false);
        when(backupRepository.save(any(DatabaseBackup.class))).thenAnswer(inv -> inv.getArgument(0));

        CreateBackupRequest request = new CreateBackupRequest(instanceId, "mssql-full");
        BackupResponse response = backupService.createBackup(request);

        assertNotNull(response);
        assertEquals("SQLSERVER", response.engine());
        assertTrue(response.s3Key().endsWith(".bak"));
        assertEquals("COMPLETED", response.status());
    }

    @Test
    void testCreateBackupInstanceNotFoundThrowsResourceNotFound() {
        UUID nonExistentId = UUID.randomUUID();
        when(instanceRepository.findById(nonExistentId)).thenReturn(Optional.empty());

        CreateBackupRequest request = new CreateBackupRequest(nonExistentId, "test");
        assertThrows(ResourceNotFoundException.class, () -> backupService.createBackup(request));
    }

    @Test
    void testRestoreBackupToSameInstance() {
        UUID instanceId = UUID.randomUUID();
        UUID backupId = UUID.randomUUID();
        DatabaseInstance instance = createTestInstance(instanceId, "my-pg", EngineType.POSTGRESQL);

        DatabaseBackup backup = DatabaseBackup.builder()
                .id(backupId)
                .instanceId(instanceId)
                .instanceName("my-pg")
                .name("backup-1")
                .engine("POSTGRESQL")
                .s3Bucket("lola-relate-backups")
                .s3Key("instances/" + instanceId + "/backups/" + backupId + ".dump")
                .status("COMPLETED")
                .build();

        when(backupRepository.findById(backupId)).thenReturn(Optional.of(backup));
        when(instanceRepository.findById(instanceId)).thenReturn(Optional.of(instance));
        when(dockerProvisionerService.isEnabled()).thenReturn(false);

        BackupResponse response = backupService.restoreBackup(backupId, null);

        assertNotNull(response);
        assertEquals(backupId, response.id());
        verify(storageService, times(1)).downloadSnapshot(eq(backup.getS3Key()), any());
    }

    @Test
    void testRestoreBackupToTargetInstanceWithMatchingEngine() {
        UUID sourceInstanceId = UUID.randomUUID();
        UUID targetInstanceId = UUID.randomUUID();
        UUID backupId = UUID.randomUUID();

        DatabaseInstance targetInstance = createTestInstance(targetInstanceId, "new-pg", EngineType.POSTGRESQL);

        DatabaseBackup backup = DatabaseBackup.builder()
                .id(backupId)
                .instanceId(sourceInstanceId)
                .instanceName("source-pg")
                .name("clone-source")
                .engine("POSTGRESQL")
                .s3Bucket("lola-relate-backups")
                .s3Key("instances/" + sourceInstanceId + "/backups/" + backupId + ".dump")
                .status("COMPLETED")
                .build();

        when(backupRepository.findById(backupId)).thenReturn(Optional.of(backup));
        when(instanceRepository.findById(targetInstanceId)).thenReturn(Optional.of(targetInstance));
        when(dockerProvisionerService.isEnabled()).thenReturn(false);

        RestoreBackupRequest request = new RestoreBackupRequest(targetInstanceId);
        BackupResponse response = backupService.restoreBackup(backupId, request);

        assertNotNull(response);
        assertEquals(backupId, response.id());
    }

    @Test
    void testRestoreBackupThrowsOnMismatchedEngine() {
        UUID sourceInstanceId = UUID.randomUUID();
        UUID targetInstanceId = UUID.randomUUID();
        UUID backupId = UUID.randomUUID();

        DatabaseInstance targetInstance = createTestInstance(targetInstanceId, "target-mysql", EngineType.MYSQL);

        DatabaseBackup backup = DatabaseBackup.builder()
                .id(backupId)
                .instanceId(sourceInstanceId)
                .instanceName("source-pg")
                .name("pg-dump")
                .engine("POSTGRESQL")
                .s3Bucket("lola-relate-backups")
                .s3Key("instances/" + sourceInstanceId + "/backups/" + backupId + ".dump")
                .status("COMPLETED")
                .build();

        when(backupRepository.findById(backupId)).thenReturn(Optional.of(backup));
        when(instanceRepository.findById(targetInstanceId)).thenReturn(Optional.of(targetInstance));

        RestoreBackupRequest request = new RestoreBackupRequest(targetInstanceId);
        assertThrows(InvalidOperationException.class, () -> backupService.restoreBackup(backupId, request));
    }

    @Test
    void testListBackupsByInstance() {
        UUID instanceId = UUID.randomUUID();
        DatabaseBackup b1 = DatabaseBackup.builder().id(UUID.randomUUID()).instanceId(instanceId).name("b1").status("COMPLETED").build();
        DatabaseBackup b2 = DatabaseBackup.builder().id(UUID.randomUUID()).instanceId(instanceId).name("b2").status("COMPLETED").build();

        when(backupRepository.findByInstanceIdOrderByCreatedAtDesc(instanceId)).thenReturn(List.of(b1, b2));

        List<BackupResponse> responses = backupService.listBackups(instanceId);
        assertEquals(2, responses.size());
        assertEquals("b1", responses.get(0).name());
        assertEquals("b2", responses.get(1).name());
    }

    @Test
    void testListAllBackups() {
        DatabaseBackup b1 = DatabaseBackup.builder().id(UUID.randomUUID()).name("b1").status("COMPLETED").build();
        DatabaseBackup b2 = DatabaseBackup.builder().id(UUID.randomUUID()).name("b2").status("COMPLETED").build();

        when(backupRepository.findAllByOrderByCreatedAtDesc()).thenReturn(List.of(b1, b2));

        List<BackupResponse> responses = backupService.listBackups(null);
        assertEquals(2, responses.size());
    }

    @Test
    void testGetBackupByIdFoundAndNotFound() {
        UUID id = UUID.randomUUID();
        DatabaseBackup backup = DatabaseBackup.builder().id(id).name("found-b").status("COMPLETED").build();
        when(backupRepository.findById(id)).thenReturn(Optional.of(backup));

        BackupResponse response = backupService.getBackupById(id);
        assertNotNull(response);
        assertEquals("found-b", response.name());

        UUID missingId = UUID.randomUUID();
        when(backupRepository.findById(missingId)).thenReturn(Optional.empty());
        assertThrows(ResourceNotFoundException.class, () -> backupService.getBackupById(missingId));
    }

    @Test
    void testDeleteBackup() {
        UUID id = UUID.randomUUID();
        DatabaseBackup backup = DatabaseBackup.builder()
                .id(id)
                .name("delete-me")
                .s3Key("instances/u/backups/b.dump")
                .status("COMPLETED")
                .build();
        when(backupRepository.findById(id)).thenReturn(Optional.of(backup));

        backupService.deleteBackup(id);

        verify(storageService, times(1)).deleteSnapshot(eq("instances/u/backups/b.dump"));
        verify(backupRepository, times(1)).delete(backup);
    }

    @Test
    void testGenerateDownloadUrlSuccess() {
        UUID id = UUID.randomUUID();
        DatabaseBackup backup = DatabaseBackup.builder()
                .id(id)
                .name("download-b")
                .s3Key("instances/u/backups/download-b.dump")
                .sizeBytes(12345L)
                .status("COMPLETED")
                .build();
        when(backupRepository.findById(id)).thenReturn(Optional.of(backup));
        when(storageService.generateDownloadPresignedUrl(eq("instances/u/backups/download-b.dump"), eq(45L)))
                .thenReturn("http://s3.download/url");

        BackupDownloadResponse response = backupService.generateDownloadUrl(id, 45L);

        assertNotNull(response);
        assertEquals("http://s3.download/url", response.downloadUrl());
        assertEquals("download-b.dump", response.filename());
        assertEquals(12345L, response.sizeBytes());
        assertNotNull(response.expiresAt());
    }

    @Test
    void testGenerateDownloadUrlFailsIfPending() {
        UUID id = UUID.randomUUID();
        DatabaseBackup backup = DatabaseBackup.builder()
                .id(id)
                .name("pending-b")
                .status("PENDING")
                .build();
        when(backupRepository.findById(id)).thenReturn(Optional.of(backup));

        assertThrows(InvalidOperationException.class, () -> backupService.generateDownloadUrl(id, 60L));
    }
}
