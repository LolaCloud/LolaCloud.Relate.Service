package com.miranda.luna.relate.infrastructure.web.controller;

import com.miranda.luna.relate.application.service.InstanceService;
import com.miranda.luna.relate.infrastructure.web.dto.RelateStatusResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/relate/status")
public class RelateStatusController {

    private final InstanceService instanceService;

    public RelateStatusController(InstanceService instanceService) {
        this.instanceService = instanceService;
    }

    @GetMapping
    public ResponseEntity<RelateStatusResponse> getStatus() {
        return ResponseEntity.ok(instanceService.getStatusSummary());
    }
}
