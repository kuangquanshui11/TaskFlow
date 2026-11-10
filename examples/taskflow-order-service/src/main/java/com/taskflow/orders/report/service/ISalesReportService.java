package com.taskflow.orders.report.service;
import com.taskflow.orders.report.dto.SalesProtocol.*;
public interface ISalesReportService {
    Report insert(Generate request);
    Report update(Revise request);
    Page select(Long shopId, int page, int pageSize);
    Page selectRevisions(String id, int page, int pageSize);
    Detail selectById(String id);
    Export selectExport(String id);
}
