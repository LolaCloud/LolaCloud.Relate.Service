package com.miranda.luna.relate.infrastructure.docker;

import com.miranda.luna.relate.domain.model.DatabaseInstance;
import com.miranda.luna.relate.domain.model.EngineType;
import com.miranda.luna.relate.domain.model.InstanceTier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
public class DockerProvisionerService {

    private static final Logger logger = LoggerFactory.getLogger(DockerProvisionerService.class);

    private final boolean enabled;
    private final boolean strict;
    private final long commandTimeoutSeconds;
    private final DockerCommandRunner commandRunner;

    @Autowired
    public DockerProvisionerService(
            @Value("${lola.relate.docker.enabled:true}") boolean enabled,
            @Value("${lola.relate.docker.strict:false}") boolean strict,
            @Value("${lola.relate.docker.command-timeout-seconds:60}") long commandTimeoutSeconds,
            DockerCommandRunner commandRunner
    ) {
        this.enabled = enabled;
        this.strict = strict;
        this.commandTimeoutSeconds = commandTimeoutSeconds;
        this.commandRunner = commandRunner;
    }

    public DockerProvisionerService(
            boolean enabled,
            boolean strict,
            DockerCommandRunner commandRunner
    ) {
        this(enabled, strict, 60, commandRunner);
    }

    public boolean isEnabled() {
        return enabled;
    }

    public boolean isStrict() {
        return strict;
    }

    public boolean isDockerAvailable() {
        if (!enabled) {
            return false;
        }
        try {
            CommandResult result = commandRunner.run(List.of("docker", "ps"), 5);
            return result.isSuccess();
        } catch (Exception e) {
            logger.debug("Docker availability check failed: {}", e.getMessage());
            return false;
        }
    }

    public String provisionContainer(DatabaseInstance instance) {
        String containerName = "lola-relate-db-" + instance.getName();
        String volumeName = "lola_relate_data_" + instance.getName();

        if (!enabled || !isDockerAvailable()) {
            if (strict) {
                throw new DockerProvisionerException("Docker is unavailable or disabled, but strict mode is enabled");
            }
            logger.warn("Docker is unavailable or disabled. Returning simulated container ID for instance '{}'", instance.getName());
            return "sim-" + UUID.randomUUID();
        }

        List<String> command = buildRunCommand(instance, containerName, volumeName);

        try {
            logger.info("Executing docker run for instance '{}' (engine: {}, port: {})",
                    instance.getName(), instance.getEngine(), instance.getInternalPort());
            CommandResult result = commandRunner.run(command, commandTimeoutSeconds);

            if (result.isSuccess()) {
                String stdout = result.stdout().trim();
                String lastLine = stdout.lines().reduce((first, second) -> second).orElse("").trim();
                String containerId = lastLine.length() >= 12 ? lastLine.substring(0, 12) : lastLine;

                if (containerId.isBlank()) {
                    throw new DockerProvisionerException("Docker run succeeded but container ID was empty");
                }

                logger.info("Successfully provisioned Docker container '{}' for instance '{}'", containerId, instance.getName());
                return containerId;
            } else {
                logger.warn("Failed to provision container for instance '{}', exitCode: {}, stderr: {}",
                        instance.getName(), result.exitCode(), result.stderr());
                if (strict) {
                    throw new DockerProvisionerException("Failed to provision container: " + result.stderr());
                }
                return "sim-" + UUID.randomUUID();
            }
        } catch (Exception e) {
            logger.warn("Exception provisioning container for instance '{}': {}", instance.getName(), e.getMessage());
            if (strict) {
                if (e instanceof DockerProvisionerException dpe) {
                    throw dpe;
                }
                throw new DockerProvisionerException("Failed to provision container: " + e.getMessage(), e);
            }
            return "sim-" + UUID.randomUUID();
        }
    }

    public void startContainer(String containerId) {
        if (containerId == null || containerId.isBlank()) {
            logger.warn("Cannot start container: containerId is blank");
            return;
        }
        if (containerId.startsWith("sim-") || !enabled || !isDockerAvailable()) {
            if (strict) {
                throw new DockerProvisionerException("Cannot start container " + containerId + ": Docker unavailable or strict mode enabled");
            }
            logger.info("Skipping Docker start for container '{}' (offline / simulated mode)", containerId);
            return;
        }

        try {
            logger.info("Starting Docker container '{}'", containerId);
            CommandResult result = commandRunner.run(List.of("docker", "start", containerId), commandTimeoutSeconds);
            if (!result.isSuccess()) {
                logger.warn("docker start failed for '{}': {}", containerId, result.stderr());
                if (strict) {
                    throw new DockerProvisionerException("Failed to start container " + containerId + ": " + result.stderr());
                }
            }
        } catch (Exception e) {
            logger.warn("Error starting Docker container '{}': {}", containerId, e.getMessage());
            if (strict) {
                if (e instanceof DockerProvisionerException dpe) {
                    throw dpe;
                }
                throw new DockerProvisionerException("Failed to start container " + containerId + ": " + e.getMessage(), e);
            }
        }
    }

    public void stopContainer(String containerId) {
        if (containerId == null || containerId.isBlank()) {
            logger.warn("Cannot stop container: containerId is blank");
            return;
        }
        if (containerId.startsWith("sim-") || !enabled || !isDockerAvailable()) {
            if (strict) {
                throw new DockerProvisionerException("Cannot stop container " + containerId + ": Docker unavailable or strict mode enabled");
            }
            logger.info("Skipping Docker stop for container '{}' (offline / simulated mode)", containerId);
            return;
        }

        try {
            logger.info("Stopping Docker container '{}'", containerId);
            CommandResult result = commandRunner.run(List.of("docker", "stop", containerId), commandTimeoutSeconds);
            if (!result.isSuccess()) {
                logger.warn("docker stop failed for '{}': {}", containerId, result.stderr());
                if (strict) {
                    throw new DockerProvisionerException("Failed to stop container " + containerId + ": " + result.stderr());
                }
            }
        } catch (Exception e) {
            logger.warn("Error stopping Docker container '{}': {}", containerId, e.getMessage());
            if (strict) {
                if (e instanceof DockerProvisionerException dpe) {
                    throw dpe;
                }
                throw new DockerProvisionerException("Failed to stop container " + containerId + ": " + e.getMessage(), e);
            }
        }
    }

    public void rebootContainer(String containerId) {
        if (containerId == null || containerId.isBlank()) {
            logger.warn("Cannot reboot container: containerId is blank");
            return;
        }
        if (containerId.startsWith("sim-") || !enabled || !isDockerAvailable()) {
            if (strict) {
                throw new DockerProvisionerException("Cannot reboot container " + containerId + ": Docker unavailable or strict mode enabled");
            }
            logger.info("Skipping Docker restart for container '{}' (offline / simulated mode)", containerId);
            return;
        }

        try {
            logger.info("Restarting Docker container '{}'", containerId);
            CommandResult result = commandRunner.run(List.of("docker", "restart", containerId), commandTimeoutSeconds);
            if (!result.isSuccess()) {
                logger.warn("docker restart failed for '{}': {}", containerId, result.stderr());
                if (strict) {
                    throw new DockerProvisionerException("Failed to restart container " + containerId + ": " + result.stderr());
                }
            }
        } catch (Exception e) {
            logger.warn("Error restarting Docker container '{}': {}", containerId, e.getMessage());
            if (strict) {
                if (e instanceof DockerProvisionerException dpe) {
                    throw dpe;
                }
                throw new DockerProvisionerException("Failed to restart container " + containerId + ": " + e.getMessage(), e);
            }
        }
    }

    public void deleteContainer(String containerId, String instanceName) {
        if (containerId == null || containerId.isBlank() || containerId.startsWith("sim-") || !enabled || !isDockerAvailable()) {
            if (strict && (containerId != null && !containerId.startsWith("sim-"))) {
                throw new DockerProvisionerException("Docker is unavailable or strict mode enabled");
            }
            logger.info("Skipping Docker deletion for container '{}' (offline / simulated mode)", containerId);
            return;
        }

        // 1. docker stop <containerId> (with timeout)
        try {
            logger.info("Stopping container '{}' prior to removal", containerId);
            commandRunner.run(List.of("docker", "stop", containerId), 10);
        } catch (Exception e) {
            logger.warn("Non-fatal error stopping container '{}' before removal: {}", containerId, e.getMessage());
        }

        // 2. docker rm -f <containerId>
        try {
            logger.info("Removing container '{}'", containerId);
            CommandResult rmResult = commandRunner.run(List.of("docker", "rm", "-f", containerId), commandTimeoutSeconds);
            if (!rmResult.isSuccess()) {
                logger.warn("docker rm -f failed for '{}': {}", containerId, rmResult.stderr());
            }
        } catch (Exception e) {
            logger.warn("Error removing Docker container '{}': {}", containerId, e.getMessage());
            if (strict) {
                throw new DockerProvisionerException("Failed to remove container " + containerId + ": " + e.getMessage(), e);
            }
        }

        // 3. docker volume rm lola_relate_data_<instanceName> (best-effort)
        if (instanceName != null && !instanceName.isBlank()) {
            String volumeName = "lola_relate_data_" + instanceName;
            try {
                logger.info("Removing volume '{}' (best-effort)", volumeName);
                CommandResult volResult = commandRunner.run(List.of("docker", "volume", "rm", volumeName), commandTimeoutSeconds);
                if (!volResult.isSuccess()) {
                    logger.debug("docker volume rm for '{}' exited with {}: {}", volumeName, volResult.exitCode(), volResult.stderr());
                }
            } catch (Exception e) {
                logger.debug("Best-effort volume removal failed for '{}': {}", volumeName, e.getMessage());
            }
        }
    }

    public String getContainerStatus(String containerId) {
        if (containerId == null || containerId.isBlank()) {
            return "unknown";
        }
        if (containerId.startsWith("sim-") || !enabled || !isDockerAvailable()) {
            if (strict) {
                throw new DockerProvisionerException("Docker is unavailable or strict mode enabled");
            }
            return "running";
        }

        try {
            CommandResult result = commandRunner.run(
                    List.of("docker", "inspect", "--format", "{{.State.Status}}", containerId),
                    10
            );
            if (result.isSuccess()) {
                String status = result.stdout().trim();
                return status.isEmpty() ? "unknown" : status;
            } else {
                logger.warn("Failed to inspect container '{}': {}", containerId, result.stderr());
                if (strict) {
                    throw new DockerProvisionerException("Failed to inspect container " + containerId + ": " + result.stderr());
                }
                return "unknown";
            }
        } catch (Exception e) {
            logger.warn("Error inspecting container '{}': {}", containerId, e.getMessage());
            if (strict) {
                if (e instanceof DockerProvisionerException dpe) {
                    throw dpe;
                }
                throw new DockerProvisionerException("Failed to inspect container " + containerId + ": " + e.getMessage(), e);
            }
            return "unknown";
        }
    }

    public List<String> buildRunCommand(DatabaseInstance instance, String containerName, String volumeName) {
        List<String> command = new ArrayList<>();
        command.add("docker");
        command.add("run");
        command.add("-d");
        command.add("--name");
        command.add(containerName);

        // Network
        command.add("--network");
        command.add("lola-network");

        // Internal port mapping: -p 127.0.0.1:<internalPort>:<enginePort>
        command.add("-p");
        command.add("127.0.0.1:" + instance.getInternalPort() + ":" + instance.getEngine().getDefaultPort());

        // Volume mount: -v lola_relate_data_<name>:<mountPath>
        command.add("-v");
        command.add(volumeName + ":" + resolveDataPath(instance.getEngine()));

        // Memory limits
        command.add("-m");
        command.add(resolveMemoryLimit(instance.getTier()));

        // Labels
        command.add("--label");
        command.add("lola.service=relate");
        command.add("--label");
        command.add("lola.relate.instance-id=" + (instance.getId() != null ? instance.getId().toString() : ""));
        command.add("--label");
        command.add("lola.relate.instance-name=" + instance.getName());
        command.add("--label");
        command.add("lola.relate.lrn=" + instance.getLrn());

        // Traefik labels for automatic Layer 4 SNI routing
        String routerName = "db-" + instance.getName();
        String serviceName = "db-" + instance.getName() + "-svc";
        String entryPoint = instance.getEngine() != null ? instance.getEngine().getTraefikEntryPoint() : "postgres";
        int enginePort = instance.getEngine() != null ? instance.getEngine().getDefaultPort() : 5432;

        command.add("--label");
        command.add("traefik.enable=true");
        command.add("--label");
        command.add("traefik.docker.network=lola-network");
        command.add("--label");
        command.add("traefik.tcp.routers." + routerName + ".rule=HostSNI(`" + instance.getHostSni() + "`)");
        command.add("--label");
        command.add("traefik.tcp.routers." + routerName + ".entrypoints=" + entryPoint);
        command.add("--label");
        command.add("traefik.tcp.routers." + routerName + ".tls=true");
        command.add("--label");
        command.add("traefik.tcp.services." + serviceName + ".loadbalancer.server.port=" + enginePort);

        // Environment variables
        switch (instance.getEngine()) {
            case POSTGRESQL -> {
                command.add("-e");
                command.add("POSTGRES_USER=" + (instance.getAdminUsername() != null ? instance.getAdminUsername() : "postgres"));
                command.add("-e");
                command.add("POSTGRES_PASSWORD=" + (instance.getAdminPassword() != null ? instance.getAdminPassword() : ""));
                command.add("-e");
                command.add("POSTGRES_DB=" + (instance.getDefaultDatabase() != null ? instance.getDefaultDatabase() : ""));
            }
            case MYSQL -> {
                command.add("-e");
                command.add("MYSQL_ROOT_PASSWORD=" + (instance.getAdminPassword() != null ? instance.getAdminPassword() : ""));
                command.add("-e");
                command.add("MYSQL_DATABASE=" + (instance.getDefaultDatabase() != null ? instance.getDefaultDatabase() : ""));
                command.add("-e");
                command.add("MYSQL_USER=" + (instance.getAdminUsername() != null ? instance.getAdminUsername() : "root"));
                command.add("-e");
                command.add("MYSQL_PASSWORD=" + (instance.getAdminPassword() != null ? instance.getAdminPassword() : ""));
            }
            case SQLSERVER -> {
                command.add("-e");
                command.add("ACCEPT_EULA=Y");
                command.add("-e");
                command.add("MSSQL_SA_PASSWORD=" + (instance.getAdminPassword() != null ? instance.getAdminPassword() : ""));
            }
        }

        // Engine image
        command.add(resolveImage(instance.getEngine(), instance.getVersion()));

        return command;
    }

    private String resolveImage(EngineType engine, String version) {
        return switch (engine) {
            case POSTGRESQL -> "postgres:" + (version != null && !version.isBlank() ? version : "17") + "-alpine";
            case MYSQL -> "mysql:" + (version != null && !version.isBlank() ? version : "8.0");
            case SQLSERVER -> "mcr.microsoft.com/mssql/server:" + (version != null && !version.isBlank() ? version : "2022") + "-latest";
        };
    }

    private String resolveDataPath(EngineType engine) {
        return switch (engine) {
            case POSTGRESQL -> "/var/lib/postgresql/data";
            case MYSQL -> "/var/lib/mysql";
            case SQLSERVER -> "/var/opt/mssql";
        };
    }

    private String resolveMemoryLimit(InstanceTier tier) {
        if (tier == null) {
            return "1024m";
        }
        return switch (tier) {
            case MICRO_512MB -> "512m";
            case STANDARD_1GB -> "1024m";
            case PRO_2GB -> "2048m";
        };
    }
}
