package com.taskflow.admin.reconciliation.controller;

import java.util.UUID;
import com.taskflow.admin.reconciliation.model.ReconciliationRecords.*;
import com.taskflow.admin.reconciliation.service.IReconciliationService;
import com.taskflow.admin.reconciliation.service.IAutomaticReconciliationService;
import com.taskflow.admin.system.dto.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/config-reconciliation")
public class ReconciliationController {
    private final IReconciliationService service;
    private final IAutomaticReconciliationService automatic;
    public ReconciliationController(IReconciliationService service, IAutomaticReconciliationService automatic) {
        this.service = service;
        this.automatic = automatic;
    }
    @GetMapping("/selectLatestRound")
    public ApiResponse<AutomaticStatus> selectLatestRound() { return ApiResponse.success(automatic.selectLatest()); }
    @GetMapping("/selectDifferences")
    public ApiResponse<DifferencePage> selectDifferences(@RequestParam UUID id, @RequestParam(defaultValue = "0") long afterJobId,
                                                         @RequestParam(defaultValue = "20") int batchSize) {
        return ApiResponse.success(automatic.selectDifferences(id, afterJobId, batchSize));
    }
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
