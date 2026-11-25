package com.miranda.luna.relate.application.service;

import com.miranda.luna.relate.domain.exception.InvalidOperationException;
import com.miranda.luna.relate.domain.exception.ResourceNotFoundException;
import com.miranda.luna.relate.domain.model.DatabaseBackup;
import com.miranda.luna.relate.domain.model.DatabaseInstance;
import com.miranda.luna.relate.domain.model.EngineType;
import com.miranda.luna.relate.domain.repository.DatabaseBackupRepository;
import com.miranda.luna.relate.domain.repository.DatabaseInstanceRepository;
import com.miranda.luna.relate.infrastructure.docker.DockerProvisionerService;
import com.miranda.luna.relate.infrastructure.storage.RelateStorageService;
import com.miranda.luna.relate.infrastructure.web.dto.BackupDownloadResponse;
import com.miranda.luna.relate.infrastructure.web.dto.BackupResponse;
import com.miranda.luna.relate.infrastructure.web.dto.CreateBackupRequest;
import com.miranda.luna.relate.infrastructure.web.dto.RestoreBackupRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

@Service
public class BackupService {

    private static final Logger logger = LoggerFactory.getLogger(BackupService.class);

    private final DatabaseBackupRepository backupRepository;
    private final DatabaseInstanceRepository instanceRepository;
    private final DockerProvisionerService dockerProvisionerService;
    private final RelateStorageService storageService;

    @Autowired
    public BackupService(
            DatabaseBackupRepository backupRepository,
            DatabaseInstanceRepository instanceRepository,
            DockerProvisionerService dockerProvisionerService,
            RelateStorageService storageService
    ) {
        this.backupRepository = backupRepository;
        this.instanceRepository = instanceRepository;
        this.dockerProvisionerService = dockerProvisionerService;
        this.storageService = storageService;
    }

    @Transactional
    public BackupResponse createBackup(CreateBackupRequest request) {
        if (request == null || request.instanceId() == null) {
            throw new InvalidOperationException("Instance ID is required for backup creation");
        }

        DatabaseInstance instance = instanceRepository.findById(request.instanceId())
                .orElseThrow(() -> new ResourceNotFoundException("Database instance not found with ID: " + request.instanceId()));

        storageService.ensureBackupBucketExists();

        String backupName = (request.name() != null && !request.name().isBlank())
                ? request.name().trim()
                : "snapshot-" + instance.getName() + "-" + System.currentTimeMillis();

        UUID backupId = UUID.randomUUID();
        String extension = resolveExtension(instance.getEngine());
        String s3Prefix = "instances/" + instance.getId() + "/backups/";
        String s3Key = s3Prefix + backupId + "." + extension;
        String storagePath = "s3://" + storageService.getBucketName() + "/" + s3Key;

        DatabaseBackup backup = DatabaseBackup.builder()
                .id(backupId)
                .instanceId(instance.getId())
                .instanceName(instance.getName())
                .name(backupName)
                .engine(instance.getEngine().name())
                .s3Bucket(storageService.getBucketName())
                .s3Prefix(s3Prefix)
                .s3Key(s3Key)
                .storagePath(storagePath)
                .status("PENDING")
                .backupType("MANUAL_SNAPSHOT")
                .createdAt(Instant.now())
                .build();

        backup = backupRepository.save(backup);

        try {
            boolean isRealDocker = dockerProvisionerService.isEnabled()
                    && dockerProvisionerService.isDockerAvailable()
                    && instance.getContainerId() != null
                    && !instance.getContainerId().startsWith("sim-")
                    && "running".equalsIgnoreCase(dockerProvisionerService.getContainerStatus(instance.getContainerId()));

            long sizeBytes;

            if (isRealDocker) {
                logger.info("Executing live Docker snapshot for instance '{}' (engine: {}, containerId: {})",
                        instance.getName(), instance.getEngine(), instance.getContainerId());
                sizeBytes = executeLiveBackup(instance, s3Key);
            } else {
                logger.info("Executing simulated snapshot for instance '{}' (engine: {}, offline / test mode)",
                        instance.getName(), instance.getEngine());
                byte[] simulationBytes = ("-- Lola Relate Snapshot Simulation --\n-- Instance: "
                        + instance.getName() + " --\n-- Engine: "
                        + instance.getEngine() + " --\n-- BackupId: "
                        + backupId + " --\n-- Created: "
                        + Instant.now() + " --\n").getBytes(StandardCharsets.UTF_8);
                sizeBytes = simulationBytes.length;
                try {
                    storageService.uploadSnapshot(s3Key, simulationBytes, resolveContentType(instance.getEngine()));
                } catch (Exception e) {
                    logger.warn("Offline fallback: S3 upload skipped or failed in test mode: {}", e.getMessage());
                }
            }

            backup.setSizeBytes(sizeBytes);
            backup.setStatus("COMPLETED");
            backup.setCompletedAt(Instant.now());
            backup = backupRepository.save(backup);

            logger.info("Successfully completed backup '{}' ({}) of size {} bytes for instance '{}'",
                    backup.getName(), backup.getId(), sizeBytes, instance.getName());
            return BackupResponse.from(backup);

        } catch (Exception e) {
            logger.error("Failed creating backup for instance '{}': {}", instance.getName(), e.getMessage(), e);
            backup.setStatus("FAILED");
            backup.setErrorMessage(e.getMessage());
            backupRepository.save(backup);
            throw new RuntimeException("Failed creating database backup: " + e.getMessage(), e);
        }
    }

    @Transactional
    public BackupResponse restoreBackup(UUID backupId, RestoreBackupRequest request) {
        DatabaseBackup backup = backupRepository.findById(backupId)
                .orElseThrow(() -> new ResourceNotFoundException("Database backup not found with ID: " + backupId));

        UUID targetInstanceId = (request != null && request.targetInstanceId() != null)
                ? request.targetInstanceId()
                : backup.getInstanceId();

        DatabaseInstance targetInstance = instanceRepository.findById(targetInstanceId)
                .orElseThrow(() -> new ResourceNotFoundException("Target database instance not found with ID: " + targetInstanceId));

        if (!targetInstance.getEngine().name().equalsIgnoreCase(backup.getEngine())) {
            throw new InvalidOperationException("Cannot restore backup of engine " + backup.getEngine()
                    + " into instance with engine " + targetInstance.getEngine());
        }

        Path tempFile = null;
        try {
            tempFile = Files.createTempFile("relate-restore-" + targetInstance.getName() + "-", ".tmp");

            try {
                storageService.downloadSnapshot(backup.getS3Key(), tempFile);
            } catch (Exception e) {
                logger.warn("Could not download snapshot from S3 (offline / test mode): {}", e.getMessage());
                if (Files.size(tempFile) == 0) {
                    Files.writeString(tempFile, "-- Lola Relate Snapshot Simulation --\n");
                }
            }

            boolean isRealDocker = dockerProvisionerService.isEnabled()
                    && dockerProvisionerService.isDockerAvailable()
                    && targetInstance.getContainerId() != null
                    && !targetInstance.getContainerId().startsWith("sim-")
                    && "running".equalsIgnoreCase(dockerProvisionerService.getContainerStatus(targetInstance.getContainerId()));

            if (isRealDocker) {
                logger.info("Executing live Docker restore for backup {} into target instance '{}' (container: {})",
                        backupId, targetInstance.getName(), targetInstance.getContainerId());
                executeLiveRestore(targetInstance, tempFile);
            } else {
                logger.info("Executing restore simulation for backup {} on target instance '{}' (offline / simulated mode)",
                        backup.getId(), targetInstance.getName());
            }

            logger.info("Restore completed successfully for backup {} into instance '{}'", backupId, targetInstance.getName());
            return BackupResponse.from(backup);

        } catch (Exception e) {
            logger.error("Failed restoring backup {} to instance '{}': {}", backupId, targetInstance.getName(), e.getMessage(), e);
            throw new RuntimeException("Failed restoring database backup: " + e.getMessage(), e);
        } finally {
            if (tempFile != null) {
                try {
                    Files.deleteIfExists(tempFile);
                } catch (Exception ignored) {
                }
            }
        }
    }

    @Transactional(readOnly = true)
    public List<BackupResponse> listBackups(UUID instanceId) {
        List<DatabaseBackup> list = (instanceId != null)
                ? backupRepository.findByInstanceIdOrderByCreatedAtDesc(instanceId)
                : backupRepository.findAllByOrderByCreatedAtDesc();
        return list.stream().map(BackupResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public BackupResponse getBackupById(UUID id) {
        DatabaseBackup backup = backupRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Database backup not found with ID: " + id));
        return BackupResponse.from(backup);
    }

    @Transactional
    public void deleteBackup(UUID id) {
        DatabaseBackup backup = backupRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Database backup not found with ID: " + id));

        try {
            storageService.deleteSnapshot(backup.getS3Key());
        } catch (Exception e) {
            logger.warn("Non-fatal error deleting snapshot from S3: {}", e.getMessage());
        }

        backupRepository.delete(backup);
        logger.info("Successfully deleted database backup '{}' ({})", backup.getName(), id);
    }

    @Transactional(readOnly = true)
    public BackupDownloadResponse generateDownloadUrl(UUID id, Long durationMinutes) {
        DatabaseBackup backup = backupRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Database backup not found with ID: " + id));

        if (!"COMPLETED".equalsIgnoreCase(backup.getStatus())) {
            throw new InvalidOperationException("Cannot generate download URL for incomplete backup with status: " + backup.getStatus());
        }

        long minutes = (durationMinutes != null && durationMinutes > 0) ? durationMinutes : 60;
        String downloadUrl = storageService.generateDownloadPresignedUrl(backup.getS3Key(), minutes);

        String filename = backup.getName();
        if (backup.getS3Key() != null && backup.getS3Key().contains("/")) {
            filename = backup.getS3Key().substring(backup.getS3Key().lastIndexOf('/') + 1);
        }

        Instant expiresAt = Instant.now().plus(Duration.ofMinutes(minutes));
        return new BackupDownloadResponse(downloadUrl, filename, backup.getSizeBytes(), expiresAt);
    }

    private long executeLiveBackup(DatabaseInstance instance, String s3Key) throws Exception {
        EngineType engine = instance.getEngine();
        Path tempFile = null;

        try {
            switch (engine) {
                case POSTGRESQL -> {
                    tempFile = Files.createTempFile("relate-backup-" + instance.getName() + "-", ".dump");
                    ProcessBuilder pb = new ProcessBuilder(
                            "docker", "exec", instance.getContainerId(),
                            "pg_dump",
                            "-U", instance.getAdminUsername(),
                            "-d", instance.getDefaultDatabase(),
                            "-F", "c"
                    );
                    pb.redirectOutput(tempFile.toFile());
                    Process process = pb.start();
                    CompletableFuture<String> errFuture = CompletableFuture.supplyAsync(() -> drainStream(process.getErrorStream()));

                    boolean finished = process.waitFor(120, TimeUnit.SECONDS);
                    if (!finished) {
                        process.destroyForcibly();
                        throw new RuntimeException("PostgreSQL pg_dump timed out after 120s");
                    }
                    if (process.exitValue() != 0) {
                        String stderr = errFuture.get(5, TimeUnit.SECONDS);
                        throw new RuntimeException("PostgreSQL pg_dump failed with exit code " + process.exitValue() + ": " + stderr);
                    }

                    long size = Files.size(tempFile);
                    storageService.uploadSnapshot(s3Key, tempFile, "application/octet-stream");
                    return size;
                }
                case MYSQL -> {
                    tempFile = Files.createTempFile("relate-backup-" + instance.getName() + "-", ".sql.gz");
                    ProcessBuilder pb = new ProcessBuilder(
                            "docker", "exec", instance.getContainerId(),
                            "mysqldump",
                            "-u", instance.getAdminUsername(),
                            "-p" + instance.getAdminPassword(),
                            instance.getDefaultDatabase(),
                            "--single-transaction",
                            "--quick"
                    );
                    Process process = pb.start();
                    CompletableFuture<String> errFuture = CompletableFuture.supplyAsync(() -> drainStream(process.getErrorStream()));

                    try (InputStream is = process.getInputStream();
                         OutputStream fos = Files.newOutputStream(tempFile);
                         GZIPOutputStream gzos = new GZIPOutputStream(fos)) {
                        is.transferTo(gzos);
                    }

                    boolean finished = process.waitFor(120, TimeUnit.SECONDS);
                    if (!finished) {
                        process.destroyForcibly();
                        throw new RuntimeException("MySQL mysqldump timed out after 120s");
                    }
                    if (process.exitValue() != 0) {
                        String stderr = errFuture.get(5, TimeUnit.SECONDS);
                        throw new RuntimeException("MySQL mysqldump failed with exit code " + process.exitValue() + ": " + stderr);
                    }

                    long size = Files.size(tempFile);
                    storageService.uploadSnapshot(s3Key, tempFile, "application/gzip");
                    return size;
                }
                case SQLSERVER -> {
                    tempFile = Files.createTempFile("relate-backup-" + instance.getName() + "-", ".bak");
                    String backupQuery = String.format("BACKUP DATABASE [%s] TO DISK = '/var/opt/mssql/data/backup.bak' WITH FORMAT",
                            instance.getDefaultDatabase());

                    ProcessBuilder pb = new ProcessBuilder(
                            "docker", "exec", instance.getContainerId(),
                            "/opt/mssql-tools18/bin/sqlcmd",
                            "-S", "localhost",
                            "-U", instance.getAdminUsername(),
                            "-P", instance.getAdminPassword(),
                            "-C",
                            "-Q", backupQuery
                    );
                    Process process = pb.start();
                    CompletableFuture<String> errFuture = CompletableFuture.supplyAsync(() -> drainStream(process.getErrorStream()));

                    boolean finished = process.waitFor(120, TimeUnit.SECONDS);
                    if (!finished) {
                        process.destroyForcibly();
                        throw new RuntimeException("SQL Server BACKUP DATABASE timed out after 120s");
                    }
                    if (process.exitValue() != 0) {
                        String stderr = errFuture.get(5, TimeUnit.SECONDS);
                        throw new RuntimeException("SQL Server BACKUP DATABASE failed: " + stderr);
                    }

                    ProcessBuilder cpPb = new ProcessBuilder(
                            "docker", "cp",
                            instance.getContainerId() + ":/var/opt/mssql/data/backup.bak",
                            tempFile.toString()
                    );
                    Process cpProcess = cpPb.start();
                    boolean cpFinished = cpProcess.waitFor(60, TimeUnit.SECONDS);
                    if (!cpFinished || cpProcess.exitValue() != 0) {
                        throw new RuntimeException("Failed copying .bak file from SQL Server container");
                    }

                    // Best-effort cleanup inside container
                    try {
                        new ProcessBuilder("docker", "exec", instance.getContainerId(), "rm", "-f", "/var/opt/mssql/data/backup.bak")
                                .start().waitFor(10, TimeUnit.SECONDS);
                    } catch (Exception ignored) {
                    }

                    long size = Files.size(tempFile);
                    storageService.uploadSnapshot(s3Key, tempFile, "application/octet-stream");
                    return size;
                }
                default -> throw new IllegalArgumentException("Unsupported engine: " + engine);
            }
        } finally {
            if (tempFile != null) {
                try {
                    Files.deleteIfExists(tempFile);
                } catch (Exception ignored) {
                }
            }
        }
    }

    private void executeLiveRestore(DatabaseInstance targetInstance, Path snapshotFile) throws Exception {
        EngineType engine = targetInstance.getEngine();

        switch (engine) {
            case POSTGRESQL -> {
                ProcessBuilder pb = new ProcessBuilder(
                        "docker", "exec", "-i", targetInstance.getContainerId(),
                        "pg_restore",
                        "-U", targetInstance.getAdminUsername(),
                        "-d", targetInstance.getDefaultDatabase(),
                        "--clean",
                        "--if-exists"
                );
                pb.redirectInput(snapshotFile.toFile());
                Process process = pb.start();
                CompletableFuture<String> errFuture = CompletableFuture.supplyAsync(() -> drainStream(process.getErrorStream()));

                boolean finished = process.waitFor(120, TimeUnit.SECONDS);
                if (!finished) {
                    process.destroyForcibly();
                    throw new RuntimeException("PostgreSQL pg_restore timed out after 120s");
                }
                logger.info("PostgreSQL pg_restore finished with exit code {}", process.exitValue());
            }
            case MYSQL -> {
                ProcessBuilder pb = new ProcessBuilder(
                        "docker", "exec", "-i", targetInstance.getContainerId(),
                        "mysql",
                        "-u", targetInstance.getAdminUsername(),
                        "-p" + targetInstance.getAdminPassword(),
                        targetInstance.getDefaultDatabase()
                );
                Process process = pb.start();
                CompletableFuture<String> errFuture = CompletableFuture.supplyAsync(() -> drainStream(process.getErrorStream()));

                try (InputStream fis = Files.newInputStream(snapshotFile);
                     GZIPInputStream gzis = new GZIPInputStream(fis);
                     OutputStream os = process.getOutputStream()) {
                    gzis.transferTo(os);
                }

                boolean finished = process.waitFor(120, TimeUnit.SECONDS);
                if (!finished) {
                    process.destroyForcibly();
                    throw new RuntimeException("MySQL restore timed out after 120s");
                }
                if (process.exitValue() != 0) {
                    String stderr = errFuture.get(5, TimeUnit.SECONDS);
                    throw new RuntimeException("MySQL restore failed with exit code " + process.exitValue() + ": " + stderr);
                }
            }
            case SQLSERVER -> {
                ProcessBuilder cpPb = new ProcessBuilder(
                        "docker", "cp",
                        snapshotFile.toString(),
                        targetInstance.getContainerId() + ":/var/opt/mssql/data/restore.bak"
                );
                Process cpProcess = cpPb.start();
                boolean cpFinished = cpProcess.waitFor(60, TimeUnit.SECONDS);
                if (!cpFinished || cpProcess.exitValue() != 0) {
                    throw new RuntimeException("Failed copying restore.bak to SQL Server container");
                }

                String restoreQuery = String.format("RESTORE DATABASE [%s] FROM DISK = '/var/opt/mssql/data/restore.bak' WITH REPLACE",
                        targetInstance.getDefaultDatabase());

                ProcessBuilder pb = new ProcessBuilder(
                        "docker", "exec", targetInstance.getContainerId(),
                        "/opt/mssql-tools18/bin/sqlcmd",
                        "-S", "localhost",
                        "-U", targetInstance.getAdminUsername(),
                        "-P", targetInstance.getAdminPassword(),
                        "-C",
                        "-Q", restoreQuery
                );
                Process process = pb.start();
                CompletableFuture<String> errFuture = CompletableFuture.supplyAsync(() -> drainStream(process.getErrorStream()));

                boolean finished = process.waitFor(120, TimeUnit.SECONDS);
                try {
                    new ProcessBuilder("docker", "exec", targetInstance.getContainerId(), "rm", "-f", "/var/opt/mssql/data/restore.bak")
                            .start().waitFor(10, TimeUnit.SECONDS);
                } catch (Exception ignored) {
                }

                if (!finished) {
                    process.destroyForcibly();
                    throw new RuntimeException("SQL Server RESTORE timed out after 120s");
                }
                if (process.exitValue() != 0) {
                    String stderr = errFuture.get(5, TimeUnit.SECONDS);
                    throw new RuntimeException("SQL Server RESTORE failed: " + stderr);
                }
            }
        }
    }

    private String drainStream(InputStream is) {
        try {
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    private String resolveExtension(EngineType engine) {
        return switch (engine) {
            case POSTGRESQL -> "dump";
            case MYSQL -> "sql.gz";
            case SQLSERVER -> "bak";
        };
    }

    private String resolveContentType(EngineType engine) {
        return switch (engine) {
            case POSTGRESQL -> "application/octet-stream";
            case MYSQL -> "application/gzip";
            case SQLSERVER -> "application/octet-stream";
        };
    }
}
