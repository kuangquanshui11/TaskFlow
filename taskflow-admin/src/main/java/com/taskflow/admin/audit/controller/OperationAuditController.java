package com.taskflow.admin.audit.controller;

import java.time.Instant;
import java.util.UUID;
import com.taskflow.admin.audit.model.OperationAudit.Action;
import com.taskflow.admin.audit.model.OperationAudit.Page;
import com.taskflow.admin.audit.model.OperationAudit.Query;
import com.taskflow.admin.audit.service.IOperationAuditService;
import com.taskflow.admin.system.dto.ApiResponse;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/audits")
public class OperationAuditController {
    private final IOperationAuditService audit;

    public OperationAuditController(IOperationAuditService audit) {
        this.audit = audit;
    }

    @GetMapping("/select")
    public ApiResponse<Page> select(@RequestParam(required = false) Instant from, @RequestParam(required = false) Instant to,
            @RequestParam(required = false) String actor, @RequestParam(required = false) Action action,
            @RequestParam(required = false) Long jobId, @RequestParam(required = false) UUID runId,
            @RequestParam(required = false) UUID requestKey, @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "25") int pageSize) {
        return ApiResponse.success(audit.select(new Query(from, to, actor, action, jobId, runId, requestKey, page, pageSize)));
    }
}
