package com.taskflow.admin.audit.service;

import java.util.UUID;
import com.taskflow.admin.audit.model.OperationAudit;
import com.taskflow.admin.audit.model.OperationAudit.Action;
import com.taskflow.admin.audit.model.OperationAudit.Page;
import com.taskflow.admin.audit.model.OperationAudit.Query;
import org.springframework.security.access.prepost.PreAuthorize;
import tools.jackson.databind.JsonNode;

public interface IOperationAuditService {
    void jobApplied(Action action, long jobId, long version);
    void jobRejected(Action action, Long jobId, Long version, String code);
    OperationAudit beginManual(UUID key, long jobId, long version);
    void observeRun(OperationAudit audit, JsonNode run);
    void rejectManual(OperationAudit audit, String code);
    void reconcile();

    @PreAuthorize("hasAuthority('audit:read')")
    Page select(Query query);
}
