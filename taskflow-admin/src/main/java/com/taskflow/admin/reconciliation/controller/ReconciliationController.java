package com.taskflow.admin.reconciliation.controller;

import java.util.UUID;
import com.taskflow.admin.reconciliation.model.ReconciliationRecords.*;
import com.taskflow.admin.reconciliation.service.IReconciliationService;
import com.taskflow.admin.system.dto.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/config-reconciliation")
public class ReconciliationController {
    private final IReconciliationService service;
    public ReconciliationController(IReconciliationService service) { this.service = service; }
    @PostMapping("/insert")
    public ApiResponse<Scan> insert(@Valid @RequestBody ScanRequest request) {
        return ApiResponse.success(service.insert(request.requestId(), request.afterJobId(), request.batchSize()));
    }
    @GetMapping("/selectById")
    public ApiResponse<Scan> selectById(@RequestParam UUID id) { return ApiResponse.success(service.selectById(id)); }
    @PostMapping("/updateRepair")
    public ApiResponse<Repair> repair(@Valid @RequestBody RepairRequest request) {
        return ApiResponse.success(service.repair(request.requestId(), request.checkId()));
    }
    @GetMapping("/selectRepairById")
    public ApiResponse<Repair> selectRepair(@RequestParam UUID id) { return ApiResponse.success(service.selectRepair(id)); }
    public record ScanRequest(@NotNull UUID requestId, @PositiveOrZero long afterJobId, @Min(1) @Max(100) int batchSize) { }
    public record RepairRequest(@NotNull UUID requestId, @NotNull UUID checkId) { }
}
