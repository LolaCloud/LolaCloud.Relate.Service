package com.miranda.luna.relate;

import com.jayway.jsonpath.JsonPath;
import com.miranda.luna.relate.domain.model.EngineType;
import com.miranda.luna.relate.domain.model.InstanceTier;
import com.miranda.luna.relate.domain.repository.DatabaseInstanceRepository;
import com.miranda.luna.relate.infrastructure.traefik.TraefikConfigService;
import com.miranda.luna.relate.infrastructure.web.dto.CreateInstanceRequest;
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
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(locations = "classpath:application-test.properties")
class InstanceIntegrationTest {

    @Autowired
    private WebApplicationContext webApplicationContext;

    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private DatabaseInstanceRepository instanceRepository;

    @Autowired
    private TraefikConfigService traefikConfigService;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .apply(springSecurity())
                .build();
    }

    @BeforeEach
    @AfterEach
    void cleanUp() {
        instanceRepository.findAll().forEach(instance -> {
            traefikConfigService.deleteInstanceConfig(instance.getName());
        });
        instanceRepository.deleteAll();
    }

    @Test
    void testSecurityUnauthorizedWithoutToken() throws Exception {
        mockMvc.perform(get("/api/v1/relate/instances"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.data.code").value("UNAUTHORIZED"));
    }

    @Test
    void testSecurityUnauthorizedWithInvalidToken() throws Exception {
        mockMvc.perform(get("/api/v1/relate/instances")
                        .header("Authorization", "Bearer invalid-token"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.data.code").value("UNAUTHORIZED"));
    }

    @Test
    void testSecurityForbiddenWithUnprivilegedToken() throws Exception {
        mockMvc.perform(get("/api/v1/relate/instances")
                        .header("Authorization", "Bearer valid-unauthorized-token"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.data.code").value("FORBIDDEN"));
    }

    @Test
    void testFullInstanceLifecycleAndTraefikSNIRouting() throws Exception {
        // 1. Create instance
        CreateInstanceRequest createRequest = new CreateInstanceRequest(
                "ecommerce-db",
                EngineType.POSTGRESQL,
                "17",
                "admin_ecom",
                "SecretPassw0rd!",
                "ecommerce_main",
                InstanceTier.STANDARD_1GB,
                20
        );

        MvcResult createResult = mockMvc.perform(post("/api/v1/relate/instances")
                        .header("Authorization", "Bearer valid-relate-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createRequest)))
                .andExpect(status().isCreated())
                .andExpect(header().exists("X-Request-ID"))
                .andExpect(jsonPath("$.data.name").value("ecommerce-db"))
                .andExpect(jsonPath("$.data.engine").value("POSTGRESQL"))
                .andExpect(jsonPath("$.data.version").value("17"))
                .andExpect(jsonPath("$.data.status").value("RUNNING"))
                .andExpect(jsonPath("$.data.hostSni").value("ecommerce-db.db.lola-cloud.dev"))
                .andExpect(jsonPath("$.data.port").value(5432))
                .andExpect(jsonPath("$.data.internalPort").isNumber())
                .andExpect(jsonPath("$.data.connectionString").value("postgresql://admin_ecom:SecretPassw0rd!@ecommerce-db.db.lola-cloud.dev:5432/ecommerce_main?sslmode=require"))
                .andReturn();

        String responseBody = createResult.getResponse().getContentAsString();
        String instanceId = JsonPath.read(responseBody, "$.data.id");

        // Verify Traefik YAML dynamic configuration was created on disk
        Path traefikConfig = traefikConfigService.getDynamicDir().resolve("db-ecommerce-db.yaml");
        assertTrue(Files.exists(traefikConfig), "Traefik config should exist on disk");
        String yamlContent = Files.readString(traefikConfig);
        assertTrue(yamlContent.contains("rule: \"HostSNI(`ecommerce-db.db.lola-cloud.dev`)\""));
        assertTrue(yamlContent.contains("- \"postgres\""));
        assertTrue(yamlContent.contains("tls: {}"));

        // 2. Get instance by ID
        mockMvc.perform(get("/api/v1/relate/instances/" + instanceId)
                        .header("Authorization", "Bearer valid-relate-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(instanceId))
                .andExpect(jsonPath("$.data.name").value("ecommerce-db"));

        // 3. List instances
        mockMvc.perform(get("/api/v1/relate/instances")
                        .header("Authorization", "Bearer valid-relate-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value(instanceId))
                .andExpect(jsonPath("$.data[0].name").value("ecommerce-db"));

        // 4. Stop instance
        mockMvc.perform(post("/api/v1/relate/instances/" + instanceId + "/stop")
                        .header("Authorization", "Bearer valid-relate-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("STOPPED"));

        // After stop, Traefik config should be deleted so traffic is dropped
        assertFalse(Files.exists(traefikConfig), "Traefik config should be deleted when instance stopped");

        // 5. Start instance
        mockMvc.perform(post("/api/v1/relate/instances/" + instanceId + "/start")
                        .header("Authorization", "Bearer valid-relate-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("RUNNING"));

        // After start, Traefik config should be recreated
        assertTrue(Files.exists(traefikConfig), "Traefik config should be recreated when instance started");

        // 6. Reboot instance
        mockMvc.perform(post("/api/v1/relate/instances/" + instanceId + "/reboot")
                        .header("Authorization", "Bearer valid-relate-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("RUNNING"));

        // 7. Delete instance
        mockMvc.perform(delete("/api/v1/relate/instances/" + instanceId)
                        .header("Authorization", "Bearer valid-relate-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.message").value("Database instance deleted successfully"));

        // Verify entity and Traefik config are removed
        assertFalse(Files.exists(traefikConfig), "Traefik config should be deleted when instance deleted");
        assertFalse(instanceRepository.existsById(java.util.UUID.fromString(instanceId)));
    }

    @Test
    void testListRunwayZonesEndpoint() throws Exception {
        mockMvc.perform(get("/api/v1/relate/zones")
                        .header("Authorization", "Bearer valid-relate-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray());
    }

    @Test
    void testRunwayIntegratedInstanceLifecycle() throws Exception {
        CreateInstanceRequest createRequest = new CreateInstanceRequest(
                "runway-pg",
                EngineType.POSTGRESQL,
                "17",
                "runway_admin",
                "SecretPass123!",
                "runway_db",
                InstanceTier.STANDARD_1GB,
                10,
                true,
                "zone-cf-prod",
                "custom-domain.org",
                null,
                true
        );

        MvcResult createResult = mockMvc.perform(post("/api/v1/relate/instances")
                        .header("Authorization", "Bearer valid-relate-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createRequest)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.name").value("runway-pg"))
                .andExpect(jsonPath("$.data.runwayIntegrated").value(true))
                .andExpect(jsonPath("$.data.hostSni").value("runway-pg.custom-domain.org"))
                .andExpect(jsonPath("$.data.dnsZoneName").value("custom-domain.org"))
                .andExpect(jsonPath("$.data.runwayRecordId").isNotEmpty())
                .andExpect(jsonPath("$.data.wanIp").isNotEmpty())
                .andExpect(jsonPath("$.data.ddnsEnabled").value(true))
                .andReturn();

        String responseBody = createResult.getResponse().getContentAsString();
        String instanceId = JsonPath.read(responseBody, "$.data.id");

        // Verify Traefik dynamic config was created for the custom domain SNI
        Path traefikConfig = traefikConfigService.getDynamicDir().resolve("db-runway-pg.yaml");
        assertTrue(Files.exists(traefikConfig), "Traefik config should exist on disk");
        String yamlContent = Files.readString(traefikConfig);
        assertTrue(yamlContent.contains("rule: \"HostSNI(`runway-pg.custom-domain.org`)\""));

        // Delete the Runway-integrated instance
        mockMvc.perform(delete("/api/v1/relate/instances/" + instanceId)
                        .header("Authorization", "Bearer valid-relate-token"))
                .andExpect(status().isOk());

        assertFalse(Files.exists(traefikConfig), "Traefik config should be deleted when instance deleted");
        assertFalse(instanceRepository.existsById(java.util.UUID.fromString(instanceId)));
    }
}
