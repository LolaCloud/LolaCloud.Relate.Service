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
import com.miranda.luna.relate.infrastructure.storage.RelateStorageService;
import com.miranda.luna.relate.infrastructure.web.dto.CreateInstanceRequest;
import com.miranda.luna.relate.infrastructure.web.dto.DatabaseInstanceResponse;
import com.miranda.luna.relate.infrastructure.web.dto.RelateStatusResponse;
import com.miranda.luna.relate.infrastructure.web.dto.RestoreBackupRequest;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.security.SecureRandom;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class InstanceService {

    private static final Logger logger = LoggerFactory.getLogger(InstanceService.class);
    private static final Pattern NAME_PATTERN = Pattern.compile("^[a-zA-Z0-9]([a-zA-Z0-9-]*[a-zA-Z0-9])?$");
    private static final String PASSWORD_CHARACTERS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_#";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final DatabaseInstanceRepository instanceRepository;
    private final TraefikConfigService traefikConfigService;
    private final DockerProvisionerService dockerProvisionerService;
    private final RunwayClientPort runwayClient;
    private final String domainSuffix;
    private final RelateStorageService storageService;
    private final BackupService backupService;
    private final String journalApiUrl;
    private final java.net.http.HttpClient httpClient = java.net.http.HttpClient.newBuilder()
            .connectTimeout(java.time.Duration.ofSeconds(2))
            .build();

    @Autowired
    public InstanceService(
            DatabaseInstanceRepository instanceRepository,
            TraefikConfigService traefikConfigService,
            DockerProvisionerService dockerProvisionerService,
            @Autowired(required = false) RunwayClientPort runwayClient,
            @Value("${lola.relate.domain-suffix:db.lola-cloud.dev}") String domainSuffix,
            @Autowired(required = false) RelateStorageService storageService,
            @Autowired(required = false) @org.springframework.context.annotation.Lazy BackupService backupService,
            @Value("${lola.relate.journal.api-url:http://localhost:8087}") String journalApiUrl
    ) {
        this.instanceRepository = instanceRepository;
        this.traefikConfigService = traefikConfigService;
        this.dockerProvisionerService = dockerProvisionerService;
        this.runwayClient = runwayClient;
        this.domainSuffix = domainSuffix.startsWith(".") ? domainSuffix.substring(1) : domainSuffix;
        this.storageService = storageService;
        this.backupService = backupService;
        this.journalApiUrl = journalApiUrl;
    }

    public InstanceService(
            DatabaseInstanceRepository instanceRepository,
            TraefikConfigService traefikConfigService,
            DockerProvisionerService dockerProvisionerService,
            RunwayClientPort runwayClient,
            String domainSuffix
    ) {
        this(instanceRepository, traefikConfigService, dockerProvisionerService, runwayClient, domainSuffix, null, null, null);
    }

    public InstanceService(
            DatabaseInstanceRepository instanceRepository,
            TraefikConfigService traefikConfigService,
            DockerProvisionerService dockerProvisionerService,
            String domainSuffix
    ) {
        this(instanceRepository, traefikConfigService, dockerProvisionerService, null, domainSuffix, null, null, null);
    }

    @Transactional
    public synchronized DatabaseInstanceResponse createInstance(CreateInstanceRequest request) {
        if (request.name() == null || !NAME_PATTERN.matcher(request.name()).matches()) {
            throw new InvalidOperationException("Invalid database instance name. Name must be alphanumeric and may contain hyphens.");
        }

        String normalizedName = request.name().toLowerCase();
        if (instanceRepository.existsByName(request.name()) || instanceRepository.existsByName(normalizedName)) {
            throw new ConflictException("Database instance with name '" + request.name() + "' already exists");
        }

        boolean enableRunway = Boolean.TRUE.equals(request.enableRunwayIntegration())
                || (request.zoneId() != null && !request.zoneId().isBlank())
                || (request.zoneName() != null && !request.zoneName().isBlank());

        String hostSni;
        boolean runwayIntegrated = false;
        UUID runwayRecordId = null;
        String dnsZoneId = null;
        String dnsZoneName = null;
        String wanIp = null;
        boolean ddnsEnabled = request.enableDdns() != null ? request.enableDdns() : true;

        if (enableRunway) {
            String token = resolveBearerToken();

            if (request.hostname() != null && !request.hostname().isBlank()) {
                String candidate = request.hostname().toLowerCase().trim();
                if (request.zoneName() != null && !request.zoneName().isBlank()) {
                    String cleanZone = request.zoneName().trim().replaceAll("^\\.", "").toLowerCase();
                    if (candidate.endsWith("." + cleanZone)) {
                        String sub = candidate.substring(0, candidate.length() - cleanZone.length() - 1);
                        if (sub.contains(".")) {
                            throw new InvalidOperationException("Subdomain must be a single level (e.g. 'loja-db." + cleanZone + "'). Multi-level subdomains with dots are not supported.");
                        }
                    }
                }
                hostSni = candidate;
            } else if (request.zoneName() != null && !request.zoneName().isBlank()) {
                String cleanZone = request.zoneName().trim().replaceAll("^\\.", "");
                hostSni = normalizedName + "." + cleanZone;
            } else {
                hostSni = normalizedName + "." + domainSuffix;
            }

            if (instanceRepository.existsByHostSni(hostSni)) {
                throw new ConflictException("Database instance with host SNI '" + hostSni + "' already exists");
            }

            if (runwayClient != null) {
                wanIp = runwayClient.getCurrentWanIp(token);
            }
            if (wanIp == null || wanIp.isBlank()) {
                wanIp = "127.0.0.1";
            }

            if (runwayClient != null) {
                RunwayDnsRecordDto dnsRecord = runwayClient.createDnsRecord(
                        token,
                        request.zoneId(),
                        hostSni,
                        wanIp,
                        ddnsEnabled
                );
                if (dnsRecord != null) {
                    runwayRecordId = dnsRecord.id();
                }
            }
            if (runwayRecordId == null) {
                runwayRecordId = UUID.randomUUID();
            }

            runwayIntegrated = true;
            dnsZoneId = request.zoneId();
            dnsZoneName = request.zoneName();
        } else {
            hostSni = normalizedName + "." + domainSuffix;
            if (instanceRepository.existsByHostSni(hostSni)) {
                throw new ConflictException("Database instance with host SNI '" + hostSni + "' already exists");
            }
            ddnsEnabled = false;
        }

        EngineType engine = request.engine();
        int externalPort = engine.getDefaultPort();
        int internalPort = calculateNextInternalPort();

        String version = (request.version() != null && !request.version().isBlank())
                ? request.version()
                : engine.getDefaultVersion();

        String adminUsername = (request.adminUsername() != null && !request.adminUsername().isBlank())
                ? request.adminUsername()
                : resolveDefaultAdminUsername(engine);

        String adminPassword = (request.adminPassword() != null && !request.adminPassword().isBlank())
                ? request.adminPassword()
                : generateSecurePassword(24);

        String defaultDatabase = (request.defaultDatabase() != null && !request.defaultDatabase().isBlank())
                ? request.defaultDatabase()
                : normalizedName.replace("-", "_");

        InstanceTier tier = request.tier() != null ? request.tier() : InstanceTier.STANDARD_1GB;
        int storageGb = (request.storageGb() != null && request.storageGb() > 0) ? request.storageGb() : 10;

        String connectionString = engine.buildConnectionString(hostSni, externalPort, adminUsername, adminPassword, defaultDatabase);

        DatabaseInstance instance = DatabaseInstance.builder()
                .id(UUID.randomUUID())
                .name(request.name())
                .engine(engine)
                .version(version)
                .status(InstanceStatus.RUNNING)
                .hostSni(hostSni)
                .port(externalPort)
                .internalPort(internalPort)
                .adminUsername(adminUsername)
                .adminPassword(adminPassword)
                .defaultDatabase(defaultDatabase)
                .tier(tier)
                .storageGb(storageGb)
                .connectionString(connectionString)
                .runwayIntegrated(runwayIntegrated)
                .runwayRecordId(runwayRecordId)
                .dnsZoneId(dnsZoneId)
                .dnsZoneName(dnsZoneName)
                .wanIp(wanIp)
                .ddnsEnabled(ddnsEnabled)
                .build();

        // Provision Docker container
        String containerId = dockerProvisionerService.provisionContainer(instance);
        instance.setContainerId(containerId);

        // Generate Traefik TCP SNI router config
        traefikConfigService.writeInstanceConfig(instance);

        // Create folder hierarchy in S3 bucket
        if (storageService != null) {
            storageService.createInstanceFolders(instance.getId());
        }

        DatabaseInstance saved = instanceRepository.save(instance);

        // Mode B: Recreate / Create a NEW database from a backup
        if (request.restoreFromBackupId() != null && backupService != null) {
            logger.info("Automatically restoring instance '{}' ({}) from backup {}",
                    saved.getName(), saved.getId(), request.restoreFromBackupId());
            backupService.restoreBackup(request.restoreFromBackupId(), new RestoreBackupRequest(saved.getId()));
        }

        logger.info("Successfully created database instance '{}' ({}) with SNI {}, Runway integrated: {}, container {}",
                saved.getName(), saved.getEngine(), saved.getHostSni(), saved.getRunwayIntegrated(), saved.getContainerId());
        return DatabaseInstanceResponse.from(saved);
    }

    @Transactional(readOnly = true)
    public List<DatabaseInstanceResponse> listInstances() {
        return instanceRepository.findAll().stream()
                .map(DatabaseInstanceResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public DatabaseInstanceResponse getInstanceById(UUID id) {
        DatabaseInstance instance = findInstanceOrThrow(id);
        return DatabaseInstanceResponse.from(instance);
    }

    @Transactional
    public DatabaseInstanceResponse startInstance(UUID id) {
        DatabaseInstance instance = findInstanceOrThrow(id);
        dockerProvisionerService.startContainer(instance.getContainerId());
        instance.setStatus(InstanceStatus.RUNNING);
        traefikConfigService.writeInstanceConfig(instance);
        DatabaseInstance saved = instanceRepository.save(instance);
        logger.info("Started database instance '{}' ({})", saved.getName(), saved.getId());
        return DatabaseInstanceResponse.from(saved);
    }

    @Transactional
    public DatabaseInstanceResponse stopInstance(UUID id) {
        DatabaseInstance instance = findInstanceOrThrow(id);
        dockerProvisionerService.stopContainer(instance.getContainerId());
        instance.setStatus(InstanceStatus.STOPPED);
        traefikConfigService.deleteInstanceConfig(instance.getName());
        DatabaseInstance saved = instanceRepository.save(instance);
        logger.info("Stopped database instance '{}' ({})", saved.getName(), saved.getId());
        return DatabaseInstanceResponse.from(saved);
    }

    @Transactional
    public DatabaseInstanceResponse rebootInstance(UUID id) {
        DatabaseInstance instance = findInstanceOrThrow(id);
        dockerProvisionerService.rebootContainer(instance.getContainerId());
        instance.setStatus(InstanceStatus.RUNNING);
        traefikConfigService.writeInstanceConfig(instance);
        DatabaseInstance saved = instanceRepository.save(instance);
        logger.info("Rebooted database instance '{}' ({})", saved.getName(), saved.getId());
        return DatabaseInstanceResponse.from(saved);
    }

    @Transactional
    public void deleteInstance(UUID id) {
        DatabaseInstance instance = findInstanceOrThrow(id);
        String token = resolveBearerToken();

        // 1. Clean up Runway DNS records
        if (runwayClient != null) {
            if (Boolean.TRUE.equals(instance.getRunwayIntegrated()) && instance.getRunwayRecordId() != null) {
                try {
                    runwayClient.deleteDnsRecord(token, instance.getRunwayRecordId());
                    logger.info("Deleted Runway DNS record {} for instance '{}'", instance.getRunwayRecordId(), instance.getName());
                } catch (Exception e) {
                    logger.warn("Failed deleting Runway DNS record {} for instance '{}': {}",
                            instance.getRunwayRecordId(), instance.getName(), e.getMessage());
                }
            }
            if (instance.getHostSni() != null && !instance.getHostSni().isBlank()) {
                try {
                    runwayClient.deleteDnsRecordsByHostname(token, instance.getDnsZoneId(), instance.getHostSni());
                    logger.info("Checked and deleted all matching DNS records by hostname '{}' in zone '{}'",
                            instance.getHostSni(), instance.getDnsZoneId());
                } catch (Exception e) {
                    logger.warn("Failed deleting matching DNS records by hostname '{}': {}", instance.getHostSni(), e.getMessage());
                }
            }
        }

        // 2. Clean up Journal telemetry metrics
        purgeJournalMetrics(instance.getName(), token);

        // 3. Clean up Docker container and Traefik dynamic config
        dockerProvisionerService.deleteContainer(instance.getContainerId(), instance.getName());
        traefikConfigService.deleteInstanceConfig(instance.getName());
        instanceRepository.delete(instance);
        logger.info("Deleted database instance '{}' ({})", instance.getName(), instance.getId());
    }

    private void purgeJournalMetrics(String instanceName, String token) {
        if (journalApiUrl == null || journalApiUrl.isBlank() || instanceName == null) {
            return;
        }
        try {
            java.net.http.HttpRequest.Builder reqBuilder = java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create(journalApiUrl + "/api/v1/journal/metrics/instance/" + java.net.URLEncoder.encode(instanceName, java.nio.charset.StandardCharsets.UTF_8)))
                    .timeout(java.time.Duration.ofSeconds(2))
                    .DELETE();
            if (token != null && !token.isBlank()) {
                reqBuilder.header("Authorization", "Bearer " + token.trim());
            }
            httpClient.sendAsync(reqBuilder.build(), java.net.http.HttpResponse.BodyHandlers.discarding())
                    .thenAccept(res -> logger.info("Journal metric purge for '{}' responded with HTTP {}", instanceName, res.statusCode()))
                    .exceptionally(ex -> {
                        logger.debug("Non-critical: could not contact Journal for metric purge: {}", ex.getMessage());
                        return null;
                    });
        } catch (Exception e) {
            logger.debug("Failed initiating Journal metric purge for '{}': {}", instanceName, e.getMessage());
        }
    }

    @Transactional(readOnly = true)
    public List<RunwayZoneDto> listRunwayZones() {
        if (runwayClient == null) {
            return List.of();
        }
        String token = resolveBearerToken();
        return runwayClient.listZones(token);
    }

    @Transactional(readOnly = true)
    public RelateStatusResponse getStatusSummary() {
        List<DatabaseInstance> all = instanceRepository.findAll();
        long total = all.size();
        long running = all.stream().filter(i -> i.getStatus() == InstanceStatus.RUNNING).count();
        long stopped = all.stream().filter(i -> i.getStatus() == InstanceStatus.STOPPED).count();

        Map<String, Long> breakdown = all.stream()
                .collect(Collectors.groupingBy(i -> i.getEngine().name(), Collectors.counting()));

        return new RelateStatusResponse(
                "UP",
                "relate",
                "0.0.1-SNAPSHOT",
                total,
                running,
                stopped,
                breakdown
        );
    }

    public String resolveBearerToken() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getCredentials() instanceof String token && !token.isBlank()) {
            return token;
        }
        try {
            var requestAttributes = RequestContextHolder.getRequestAttributes();
            if (requestAttributes instanceof ServletRequestAttributes servletAttrs) {
                HttpServletRequest request = servletAttrs.getRequest();
                String authHeader = request.getHeader("Authorization");
                if (authHeader != null && authHeader.startsWith("Bearer ")) {
                    return authHeader.substring(7).trim();
                }
                Object tokenAttr = request.getAttribute("BEARER_TOKEN");
                if (tokenAttr instanceof String str && !str.isBlank()) {
                    return str;
                }
            }
        } catch (Exception ignored) {
        }
        return "relate-internal-token";
    }

    private DatabaseInstance findInstanceOrThrow(UUID id) {
        return instanceRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Database instance not found with ID: " + id));
    }

    private int calculateNextInternalPort() {
        Integer maxPort = instanceRepository.findMaxInternalPort();
        if (maxPort == null || maxPort < 15432) {
            return 15432;
        }
        return maxPort + 1;
    }

    private String resolveDefaultAdminUsername(EngineType engine) {
        return switch (engine) {
            case POSTGRESQL -> "postgres";
            case MYSQL -> "root";
            case SQLSERVER -> "sa";
        };
    }

    public static String generateSecurePassword(int length) {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            int index = RANDOM.nextInt(PASSWORD_CHARACTERS.length());
            sb.append(PASSWORD_CHARACTERS.charAt(index));
        }
        return sb.toString();
    }
}
