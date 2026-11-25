package com.miranda.luna.relate.infrastructure.traefik;

import com.miranda.luna.relate.domain.model.DatabaseInstance;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;

@Service
public class TraefikConfigService {

    private static final Logger logger = LoggerFactory.getLogger(TraefikConfigService.class);

    private final Path dynamicDir;
    private final Path traefikStaticConfigFile;

    public TraefikConfigService(
            @Value("${lola.relate.traefik.dynamic-dir:${user.dir}/traefik/dynamic}") String dynamicDirPath
    ) {
        this.dynamicDir = Paths.get(dynamicDirPath).toAbsolutePath().normalize();
        Path parent = this.dynamicDir.getParent();
        this.traefikStaticConfigFile = parent != null ? parent.resolve("traefik.yaml") : Paths.get("traefik/traefik.yaml");
    }

    @PostConstruct
    public void init() {
        try {
            Files.createDirectories(dynamicDir);
            logger.info("Initialized Traefik dynamic configuration directory at: {}", dynamicDir);
            generateStaticConfigTemplateIfNotExists();
            generateTlsOptionsTemplateIfNotExists();
        } catch (IOException e) {
            logger.error("Failed to initialize Traefik directories: {}", e.getMessage(), e);
        }
    }

    public void generateTlsOptionsTemplateIfNotExists() {
        try {
            Path tlsFile = dynamicDir.resolve("tls-options.yaml");
            if (!Files.exists(tlsFile)) {
                String content = "tls:\n" +
                        "  options:\n" +
                        "    default:\n" +
                        "      alpnProtocols:\n" +
                        "        - \"postgresql\"\n" +
                        "        - \"h2\"\n" +
                        "        - \"http/1.1\"\n";
                Files.writeString(tlsFile, content, StandardCharsets.UTF_8, StandardOpenOption.CREATE);
                logger.info("Generated Traefik default TLS options template with ALPN support at {}", tlsFile);
            }
        } catch (IOException e) {
            logger.warn("Could not write Traefik TLS options template: {}", e.getMessage());
        }
    }

    public Path writeInstanceConfig(DatabaseInstance instance) {
        String name = instance.getName();
        String entryPoint = instance.getEngine() != null
                ? instance.getEngine().getTraefikEntryPoint()
                : "postgres";
        String hostSni = instance.getHostSni();
        int enginePort = instance.getEngine() != null ? instance.getEngine().getDefaultPort() : 5432;

        String yaml = generateYamlContent(name, entryPoint, hostSni, enginePort);
        Path targetFile = dynamicDir.resolve("db-" + name + ".yaml");

        try {
            Files.createDirectories(dynamicDir);
            Files.writeString(targetFile, yaml, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE);
            logger.info("Generated Traefik dynamic config for instance '{}' at {}", name, targetFile);
            return targetFile;
        } catch (IOException e) {
            logger.error("Failed to write Traefik configuration file {}: {}", targetFile, e.getMessage(), e);
            throw new RuntimeException("Could not write Traefik configuration for database instance: " + name, e);
        }
    }

    public boolean deleteInstanceConfig(String name) {
        Path targetFile = dynamicDir.resolve("db-" + name + ".yaml");
        try {
            boolean deleted = Files.deleteIfExists(targetFile);
            if (deleted) {
                logger.info("Removed Traefik dynamic configuration file: {}", targetFile);
            } else {
                logger.debug("Traefik dynamic configuration file {} did not exist to delete", targetFile);
            }
            return deleted;
        } catch (IOException e) {
            logger.error("Failed to delete Traefik configuration file {}: {}", targetFile, e.getMessage(), e);
            throw new RuntimeException("Could not remove Traefik configuration for instance: " + name, e);
        }
    }

    public String generateYamlContent(String name, String entryPoint, String hostSni, int enginePort) {
        return "tcp:\n" +
                "  routers:\n" +
                "    db-" + name + ":\n" +
                "      entryPoints:\n" +
                "        - \"" + entryPoint + "\"\n" +
                "      rule: \"HostSNI(`" + hostSni + "`)\"\n" +
                "      service: \"db-" + name + "-svc\"\n" +
                "      tls: {}\n" +
                "  services:\n" +
                "    db-" + name + "-svc:\n" +
                "      loadBalancer:\n" +
                "        servers:\n" +
                "          - address: \"lola-relate-db-" + name + ":" + enginePort + "\"\n";
    }

    public void generateStaticConfigTemplateIfNotExists() {
        try {
            if (traefikStaticConfigFile.getParent() != null) {
                Files.createDirectories(traefikStaticConfigFile.getParent());
            }
            if (!Files.exists(traefikStaticConfigFile)) {
                String template = "# Lola Relate - Static Traefik Configuration Template\n" +
                        "# TCP EntryPoints for SNI database routing\n\n" +
                        "entryPoints:\n" +
                        "  postgres:\n" +
                        "    address: \":5432\"\n" +
                        "  mysql:\n" +
                        "    address: \":3306\"\n" +
                        "  sqlserver:\n" +
                        "    address: \":1433\"\n\n" +
                        "providers:\n" +
                        "  file:\n" +
                        "    directory: \"" + dynamicDir.toString().replace("\\", "/") + "\"\n" +
                        "    watch: true\n\n" +
                        "log:\n" +
                        "  level: INFO\n";

                Files.writeString(traefikStaticConfigFile, template, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                logger.info("Generated sample static Traefik config at {}", traefikStaticConfigFile);
            }
        } catch (IOException e) {
            logger.warn("Could not generate static Traefik template at {}: {}", traefikStaticConfigFile, e.getMessage());
        }
    }

    public Path getDynamicDir() {
        return dynamicDir;
    }

    public Path getTraefikStaticConfigFile() {
        return traefikStaticConfigFile;
    }
}
