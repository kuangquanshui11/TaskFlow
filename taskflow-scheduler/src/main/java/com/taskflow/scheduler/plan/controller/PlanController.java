package com.taskflow.scheduler.plan.controller;

import com.taskflow.scheduler.plan.dto.PlanSyncRequest;
import com.taskflow.scheduler.plan.dto.PlanResponse;
import com.taskflow.scheduler.plan.config.PlanTriggerProperties;
import com.taskflow.scheduler.plan.model.PlanRecords.*;
import com.taskflow.scheduler.plan.service.IPlanService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/internal/v1/plans")
public class PlanController {
    private final IPlanService plans;
    private final PlanTriggerProperties triggerProperties;
    public PlanController(IPlanService plans, PlanTriggerProperties triggerProperties) { this.plans = plans; this.triggerProperties = triggerProperties; }

    @PostMapping("/update")
    public Acknowledgement update(@Valid @RequestBody PlanSyncRequest request) { return plans.update(request); }

    @GetMapping("/selectByJobId")
    public PlanResponse selectByJobId(@RequestParam @Positive long id) {
        return PlanResponse.from(plans.selectByJobId(id), triggerProperties.enabled());
    }

    @GetMapping("/selectVersions")
    public java.util.List<com.taskflow.scheduler.plan.dto.PlanVersion> selectVersions(
            @RequestParam @jakarta.validation.constraints.Size(min = 1, max = 100) java.util.List<@Positive Long> ids) {
        return plans.selectVersions(ids);
    }
}
