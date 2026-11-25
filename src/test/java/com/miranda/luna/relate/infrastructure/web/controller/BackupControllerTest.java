package com.miranda.luna.relate.infrastructure.web.controller;

import com.miranda.luna.relate.application.service.BackupService;
import com.miranda.luna.relate.domain.exception.ResourceNotFoundException;
import com.miranda.luna.relate.infrastructure.web.advice.GlobalExceptionHandler;
import com.miranda.luna.relate.infrastructure.web.advice.GlobalResponseWrapperAdvice;
import com.miranda.luna.relate.infrastructure.web.dto.BackupDownloadResponse;
import com.miranda.luna.relate.infrastructure.web.dto.BackupResponse;
import com.miranda.luna.relate.infrastructure.web.dto.CreateBackupRequest;
import com.miranda.luna.relate.infrastructure.web.dto.RestoreBackupRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class BackupControllerTest {

    private MockMvc mockMvc;
    private BackupService backupService;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        backupService = mock(BackupService.class);
        objectMapper = new ObjectMapper();
        BackupController controller = new BackupController(backupService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler(), new GlobalResponseWrapperAdvice(objectMapper))
                .build();
    }

    private BackupResponse createSampleResponse(UUID id, String name) {
        return new BackupResponse(
                id,
                "lola:relate:backup:" + id,
                UUID.randomUUID(),
                "test-instance",
                name,
                "POSTGRESQL",
                1024L,
                "COMPLETED",
                "s3://lola-relate-backups/instances/u/backups/" + id + ".dump",
                "lola-relate-backups",
                "instances/u/backups/" + id + ".dump",
                "MANUAL_SNAPSHOT",
                Instant.now(),
                Instant.now(),
                null,
                "instances/u/backups/"
        );
    }

    @Test
    void testListBackups() throws Exception {
        UUID b1 = UUID.randomUUID();
        when(backupService.listBackups(null)).thenReturn(List.of(createSampleResponse(b1, "b1")));

        mockMvc.perform(get("/api/v1/relate/backups"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].id").value(b1.toString()));
    }

    @Test
    void testListBackupsWithInstanceId() throws Exception {
        UUID instId = UUID.randomUUID();
        UUID b1 = UUID.randomUUID();
        when(backupService.listBackups(instId)).thenReturn(List.of(createSampleResponse(b1, "b1")));

        mockMvc.perform(get("/api/v1/relate/backups")
                        .param("instanceId", instId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].id").value(b1.toString()));
    }

    @Test
    void testGetBackupById() throws Exception {
        UUID id = UUID.randomUUID();
        when(backupService.getBackupById(id)).thenReturn(createSampleResponse(id, "b-target"));

        mockMvc.perform(get("/api/v1/relate/backups/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(id.toString()))
                .andExpect(jsonPath("$.data.name").value("b-target"));
    }

    @Test
    void testGetBackupNotFoundReturns404() throws Exception {
        UUID id = UUID.randomUUID();
        when(backupService.getBackupById(id)).thenThrow(new ResourceNotFoundException("Backup not found"));

        mockMvc.perform(get("/api/v1/relate/backups/" + id))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.data.code").value("NOT_FOUND"));
    }

    @Test
    void testCreateBackup() throws Exception {
        UUID instId = UUID.randomUUID();
        UUID bId = UUID.randomUUID();
        CreateBackupRequest request = new CreateBackupRequest(instId, "snapshot-test");
        when(backupService.createBackup(any())).thenReturn(createSampleResponse(bId, "snapshot-test"));

        mockMvc.perform(post("/api/v1/relate/backups")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.id").value(bId.toString()))
                .andExpect(jsonPath("$.data.name").value("snapshot-test"));
    }

    @Test
    void testCreateBackupValidationFailureReturns400() throws Exception {
        CreateBackupRequest invalidRequest = new CreateBackupRequest(null, "snapshot-test");

        mockMvc.perform(post("/api/v1/relate/backups")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalidRequest)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.code").value("VALIDATION_FAILED"));
    }

    @Test
    void testRestoreBackup() throws Exception {
        UUID bId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        RestoreBackupRequest request = new RestoreBackupRequest(targetId);
        when(backupService.restoreBackup(eq(bId), any())).thenReturn(createSampleResponse(bId, "restored"));

        mockMvc.perform(post("/api/v1/relate/backups/" + bId + "/restore")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(bId.toString()));
    }

    @Test
    void testGetDownloadUrl() throws Exception {
        UUID bId = UUID.randomUUID();
        BackupDownloadResponse downloadResponse = new BackupDownloadResponse(
                "http://s3.download/sample.dump",
                "sample.dump",
                2048L,
                Instant.now().plusSeconds(3600)
        );
        when(backupService.generateDownloadUrl(eq(bId), eq(30L))).thenReturn(downloadResponse);

        mockMvc.perform(get("/api/v1/relate/backups/" + bId + "/download-url")
                        .param("durationMinutes", "30"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.downloadUrl").value("http://s3.download/sample.dump"))
                .andExpect(jsonPath("$.data.filename").value("sample.dump"))
                .andExpect(jsonPath("$.data.sizeBytes").value(2048));
    }

    @Test
    void testDeleteBackup() throws Exception {
        UUID bId = UUID.randomUUID();
        doNothing().when(backupService).deleteBackup(bId);

        mockMvc.perform(delete("/api/v1/relate/backups/" + bId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.message").value("Database backup deleted successfully"));

        verify(backupService, times(1)).deleteBackup(bId);
    }
}
