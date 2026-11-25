package com.miranda.luna.relate.infrastructure.web.controller;

import com.miranda.luna.relate.application.service.BackupService;
import com.miranda.luna.relate.infrastructure.web.dto.BackupDownloadResponse;
import com.miranda.luna.relate.infrastructure.web.dto.BackupResponse;
import com.miranda.luna.relate.infrastructure.web.dto.CreateBackupRequest;
import com.miranda.luna.relate.infrastructure.web.dto.RestoreBackupRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/relate/backups")
public class BackupController {

    private final BackupService backupService;

    public BackupController(BackupService backupService) {
        this.backupService = backupService;
    }

    @GetMapping
    public ResponseEntity<List<BackupResponse>> listBackups(@RequestParam(name = "instanceId", required = false) UUID instanceId) {
        return ResponseEntity.ok(backupService.listBackups(instanceId));
    }

    @GetMapping("/{id}")
    public ResponseEntity<BackupResponse> getBackup(@PathVariable("id") UUID id) {
        return ResponseEntity.ok(backupService.getBackupById(id));
    }

    @PostMapping
    public ResponseEntity<BackupResponse> createBackup(@Valid @RequestBody CreateBackupRequest request) {
        BackupResponse response = backupService.createBackup(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PostMapping("/{id}/restore")
    public ResponseEntity<BackupResponse> restoreBackup(
            @PathVariable("id") UUID id,
            @RequestBody(required = false) RestoreBackupRequest request
    ) {
        BackupResponse response = backupService.restoreBackup(id, request);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{id}/download-url")
    public ResponseEntity<BackupDownloadResponse> getDownloadUrl(
            @PathVariable("id") UUID id,
            @RequestParam(name = "durationMinutes", required = false) Long durationMinutes
    ) {
        BackupDownloadResponse response = backupService.generateDownloadUrl(id, durationMinutes);
        return ResponseEntity.ok(response);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, String>> deleteBackup(@PathVariable("id") UUID id) {
        backupService.deleteBackup(id);
        return ResponseEntity.ok(Map.of("message", "Database backup deleted successfully"));
    }
}
