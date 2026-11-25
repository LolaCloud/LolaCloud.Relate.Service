package com.miranda.luna.relate.infrastructure.traefik;

import com.miranda.luna.relate.domain.model.DatabaseInstance;
import com.miranda.luna.relate.domain.model.EngineType;
import com.miranda.luna.relate.domain.model.InstanceStatus;
import com.miranda.luna.relate.domain.model.InstanceTier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TraefikConfigServiceTest {

    @TempDir
    Path tempDir;

    private TraefikConfigService traefikConfigService;
    private Path dynamicDir;

    @BeforeEach
    void setUp() {
        dynamicDir = tempDir.resolve("traefik/dynamic");
        traefikConfigService = new TraefikConfigService(dynamicDir.toString());
        traefikConfigService.init();
    }

    @Test
    void testInitCreatesDirectoriesAndStaticTemplate() {
        assertTrue(Files.exists(dynamicDir));
        Path staticConfig = traefikConfigService.getTraefikStaticConfigFile();
        assertTrue(Files.exists(staticConfig));
    }

    @Test
    void testWriteInstanceConfigForPostgres() throws IOException {
        DatabaseInstance instance = DatabaseInstance.builder()
                .id(UUID.randomUUID())
                .name("test-postgres")
                .engine(EngineType.POSTGRESQL)
                .version("17")
                .status(InstanceStatus.RUNNING)
                .hostSni("test-postgres.db.lola-cloud.dev")
                .port(5432)
                .internalPort(15432)
                .adminUsername("postgres")
                .adminPassword("secret123")
                .defaultDatabase("test_db")
                .tier(InstanceTier.STANDARD_1GB)
                .build();

        Path configFile = traefikConfigService.writeInstanceConfig(instance);
        assertNotNull(configFile);
        assertTrue(Files.exists(configFile));

        String content = Files.readString(configFile);
        assertTrue(content.contains("db-test-postgres:"));
        assertTrue(content.contains("- \"postgres\""));
        assertTrue(content.contains("rule: \"HostSNI(`test-postgres.db.lola-cloud.dev`)\""));
        assertTrue(content.contains("service: \"db-test-postgres-svc\""));
        assertTrue(content.contains("address: \"lola-relate-db-test-postgres:5432\""));
    }

    @Test
    void testWriteInstanceConfigForMysql() throws IOException {
        DatabaseInstance instance = DatabaseInstance.builder()
                .id(UUID.randomUUID())
                .name("test-mysql")
                .engine(EngineType.MYSQL)
                .version("8.0")
                .status(InstanceStatus.RUNNING)
                .hostSni("test-mysql.db.lola-cloud.dev")
                .port(3306)
                .internalPort(15433)
                .adminUsername("root")
                .adminPassword("secret123")
                .defaultDatabase("test_db")
                .tier(InstanceTier.MICRO_512MB)
                .build();

        Path configFile = traefikConfigService.writeInstanceConfig(instance);
        assertNotNull(configFile);
        assertTrue(Files.exists(configFile));

        String content = Files.readString(configFile);
        assertTrue(content.contains("- \"mysql\""));
        assertTrue(content.contains("rule: \"HostSNI(`test-mysql.db.lola-cloud.dev`)\""));
        assertTrue(content.contains("address: \"lola-relate-db-test-mysql:3306\""));
    }

    @Test
    void testDeleteInstanceConfig() {
        DatabaseInstance instance = DatabaseInstance.builder()
                .id(UUID.randomUUID())
                .name("to-delete")
                .engine(EngineType.POSTGRESQL)
                .version("17")
                .status(InstanceStatus.RUNNING)
                .hostSni("to-delete.db.lola-cloud.dev")
                .port(5432)
                .internalPort(15434)
                .adminUsername("postgres")
                .adminPassword("secret123")
                .defaultDatabase("test_db")
                .tier(InstanceTier.PRO_2GB)
                .build();

        Path configFile = traefikConfigService.writeInstanceConfig(instance);
        assertTrue(Files.exists(configFile));

        boolean deleted = traefikConfigService.deleteInstanceConfig("to-delete");
        assertTrue(deleted);
        assertFalse(Files.exists(configFile));
    }
}
