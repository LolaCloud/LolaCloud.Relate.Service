package com.miranda.luna.relate;

import com.jayway.jsonpath.JsonPath;
import com.miranda.luna.relate.domain.model.DatabaseBackup;
import com.miranda.luna.relate.domain.model.DatabaseInstance;
import com.miranda.luna.relate.domain.model.EngineType;
import com.miranda.luna.relate.domain.model.InstanceStatus;
import com.miranda.luna.relate.domain.model.InstanceTier;
import com.miranda.luna.relate.domain.repository.DatabaseBackupRepository;
import com.miranda.luna.relate.domain.repository.DatabaseInstanceRepository;
import com.miranda.luna.relate.infrastructure.traefik.TraefikConfigService;
import com.miranda.luna.relate.infrastructure.web.dto.CreateBackupRequest;
import com.miranda.luna.relate.infrastructure.web.dto.CreateInstanceRequest;
import com.miranda.luna.relate.infrastructure.web.dto.RestoreBackupRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.ObjectMapper;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(locations = "classpath:application-test.properties")
class BackupIntegrationTest {

    @Autowired
    private WebApplicationContext webApplicationContext;

    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private DatabaseInstanceRepository instanceRepository;

    @Autowired
    private DatabaseBackupRepository backupRepository;

    @Autowired
    private TraefikConfigService traefikConfigService;

    private DatabaseInstance testInstance;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .apply(springSecurity())
                .build();

        cleanUp();

        testInstance = DatabaseInstance.builder()
                .id(UUID.randomUUID())
                .name("backup-test-instance")
                .engine(EngineType.POSTGRESQL)
                .version("17")
                .status(InstanceStatus.RUNNING)
                .hostSni("backup-test-instance.db.lola-cloud.dev")
                .port(5432)
                .internalPort(19432)
                .adminUsername("postgres")
                .adminPassword("testSecret123")
                .defaultDatabase("backup_test_db")
                .tier(InstanceTier.STANDARD_1GB)
                .storageGb(10)
                .connectionString("dummy")
                .containerId("sim-backup-test")
                .build();
        testInstance = instanceRepository.save(testInstance);
    }

    @AfterEach
    void tearDown() {
        cleanUp();
    }

    private void cleanUp() {
        backupRepository.deleteAll();
        instanceRepository.findAll().forEach(instance -> {
            traefikConfigService.deleteInstanceConfig(instance.getName());
        });
        instanceRepository.deleteAll();
    }

    @Test
    void testBackupSecurityRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/v1/relate/backups"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.data.code").value("UNAUTHORIZED"));
    }

    @Test
    void testCompleteBackupAndRestoreLifecycle() throws Exception {
        // 1. Create backup
        CreateBackupRequest createRequest = new CreateBackupRequest(testInstance.getId(), "manual-snapshot-1");

        MvcResult createResult = mockMvc.perform(post("/api/v1/relate/backups")
                        .header("Authorization", "Bearer valid-relate-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createRequest)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.name").value("manual-snapshot-1"))
                .andExpect(jsonPath("$.data.instanceId").value(testInstance.getId().toString()))
                .andExpect(jsonPath("$.data.instanceName").value("backup-test-instance"))
                .andExpect(jsonPath("$.data.engine").value("POSTGRESQL"))
                .andExpect(jsonPath("$.data.status").value("COMPLETED"))
                .andExpect(jsonPath("$.data.s3Bucket").value("lola-relate-backups"))
                .andExpect(jsonPath("$.data.s3Key").exists())
                .andExpect(jsonPath("$.data.lrn").exists())
                .andReturn();

        String responseBody = createResult.getResponse().getContentAsString();
        String backupIdStr = JsonPath.read(responseBody, "$.data.id");
        UUID backupId = UUID.fromString(backupIdStr);

        // 2. List backups (all)
        mockMvc.perform(get("/api/v1/relate/backups")
                        .header("Authorization", "Bearer valid-relate-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].id").value(backupIdStr));

        // 3. List backups by instanceId
        mockMvc.perform(get("/api/v1/relate/backups")
                        .header("Authorization", "Bearer valid-relate-token")
                        .param("instanceId", testInstance.getId().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].id").value(backupIdStr));

        // 4. Get backup by ID
        mockMvc.perform(get("/api/v1/relate/backups/" + backupId)
                        .header("Authorization", "Bearer valid-relate-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(backupIdStr))
                .andExpect(jsonPath("$.data.name").value("manual-snapshot-1"));

        // 5. Restore backup into same instance
        RestoreBackupRequest restoreRequest = new RestoreBackupRequest(testInstance.getId());
        mockMvc.perform(post("/api/v1/relate/backups/" + backupId + "/restore")
                        .header("Authorization", "Bearer valid-relate-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(restoreRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(backupIdStr));

        // 6. Generate presigned download URL
        mockMvc.perform(get("/api/v1/relate/backups/" + backupId + "/download-url")
                        .header("Authorization", "Bearer valid-relate-token")
                        .param("durationMinutes", "30"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.downloadUrl").exists())
                .andExpect(jsonPath("$.data.filename").exists())
                .andExpect(jsonPath("$.data.expiresAt").exists());

        // 7. Delete backup
        mockMvc.perform(delete("/api/v1/relate/backups/" + backupId)
                        .header("Authorization", "Bearer valid-relate-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.message").value("Database backup deleted successfully"));

        // Verify deleted from repository
        assertTrue(backupRepository.findById(backupId).isEmpty());
    }

    @Test
    void testRecreateDatabaseFromBackupViaCreateInstance() throws Exception {
        // 1. Create a backup for source instance
        DatabaseBackup sourceBackup = DatabaseBackup.builder()
                .id(UUID.randomUUID())
                .instanceId(testInstance.getId())
                .instanceName(testInstance.getName())
                .name("clone-base")
                .engine("POSTGRESQL")
                .s3Bucket("lola-relate-backups")
                .s3Prefix("instances/" + testInstance.getId() + "/backups/")
                .s3Key("instances/" + testInstance.getId() + "/backups/clone-base.dump")
                .storagePath("s3://lola-relate-backups/instances/" + testInstance.getId() + "/backups/clone-base.dump")
                .status("COMPLETED")
                .sizeBytes(1024L)
                .build();
        sourceBackup = backupRepository.save(sourceBackup);

        // 2. Create a NEW instance with restoreFromBackupId
        CreateInstanceRequest newInstanceReq = new CreateInstanceRequest(
                "cloned-instance",
                EngineType.POSTGRESQL,
                "17",
                "cloned_admin",
                "SuperClonedPass!",
                "cloned_db",
                InstanceTier.STANDARD_1GB,
                15,
                false,
                null,
                null,
                null,
                false,
                sourceBackup.getId()
        );

        MvcResult result = mockMvc.perform(post("/api/v1/relate/instances")
                        .header("Authorization", "Bearer valid-relate-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(newInstanceReq)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.name").value("cloned-instance"))
                .andExpect(jsonPath("$.data.engine").value("POSTGRESQL"))
                .andReturn();

        String responseBody = result.getResponse().getContentAsString();
        String clonedIdStr = JsonPath.read(responseBody, "$.data.id");
        UUID clonedId = UUID.fromString(clonedIdStr);

        DatabaseInstance cloned = instanceRepository.findById(clonedId).orElse(null);
        assertNotNull(cloned);
        assertEquals("cloned-instance", cloned.getName());
    }
}
