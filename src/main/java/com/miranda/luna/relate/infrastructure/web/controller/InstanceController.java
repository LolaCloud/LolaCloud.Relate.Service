package com.miranda.luna.relate.infrastructure.web.controller;

import com.miranda.luna.relate.application.service.InstanceService;
import com.miranda.luna.relate.infrastructure.runway.RunwayZoneDto;
import com.miranda.luna.relate.infrastructure.web.dto.CreateInstanceRequest;
import com.miranda.luna.relate.infrastructure.web.dto.DatabaseInstanceResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/relate")
public class InstanceController {

    private final InstanceService instanceService;

    public InstanceController(InstanceService instanceService) {
        this.instanceService = instanceService;
    }

    @PostMapping("/instances")
    public ResponseEntity<DatabaseInstanceResponse> createInstance(@Valid @RequestBody CreateInstanceRequest request) {
        DatabaseInstanceResponse response = instanceService.createInstance(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/instances")
    public ResponseEntity<List<DatabaseInstanceResponse>> listInstances() {
        return ResponseEntity.ok(instanceService.listInstances());
    }

    @GetMapping("/instances/{id}")
    public ResponseEntity<DatabaseInstanceResponse> getInstanceById(@PathVariable("id") UUID id) {
        return ResponseEntity.ok(instanceService.getInstanceById(id));
    }

    @PostMapping("/instances/{id}/start")
    public ResponseEntity<DatabaseInstanceResponse> startInstance(@PathVariable("id") UUID id) {
        return ResponseEntity.ok(instanceService.startInstance(id));
    }

    @PostMapping("/instances/{id}/stop")
    public ResponseEntity<DatabaseInstanceResponse> stopInstance(@PathVariable("id") UUID id) {
        return ResponseEntity.ok(instanceService.stopInstance(id));
    }

    @PostMapping("/instances/{id}/reboot")
    public ResponseEntity<DatabaseInstanceResponse> rebootInstance(@PathVariable("id") UUID id) {
        return ResponseEntity.ok(instanceService.rebootInstance(id));
    }

    @DeleteMapping("/instances/{id}")
    public ResponseEntity<Map<String, String>> deleteInstance(@PathVariable("id") UUID id) {
        instanceService.deleteInstance(id);
        return ResponseEntity.ok(Map.of("message", "Database instance deleted successfully"));
    }

    @GetMapping({"/zones", "/instances/zones"})
    public ResponseEntity<List<RunwayZoneDto>> listZones() {
        return ResponseEntity.ok(instanceService.listRunwayZones());
    }
}
