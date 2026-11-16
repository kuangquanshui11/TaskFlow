package com.taskflow.admin.reconciliation.controller;

import com.taskflow.admin.reconciliation.model.ConfigRecoveryRecords.*;
import com.taskflow.admin.reconciliation.service.IConfigRecoveryService;
import com.taskflow.admin.system.dto.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.PositiveOrZero;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/config-reconciliation")
public class ConfigRecoveryController {
    private final IConfigRecoveryService recovery;

    public ConfigRecoveryController(IConfigRecoveryService recovery) { this.recovery = recovery; }

    @GetMapping("/selectRecoveryBounds")
    public ApiResponse<Bounds> selectBounds() { return ApiResponse.success(recovery.selectBounds()); }

    @PostMapping("/updateRecovery")
    public ApiResponse<Batch> restore(@Valid @RequestBody RestoreRequest request) {
        return ApiResponse.success(recovery.restore(request.afterJobId(), request.upperJobId(), request.batchSize()));
    }

    public record RestoreRequest(@PositiveOrZero long afterJobId, @PositiveOrZero long upperJobId,
                                 @Min(1) @Max(10) int batchSize) { }
}
