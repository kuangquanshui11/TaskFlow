package com.taskflow.orders.report.service;
import com.taskflow.orders.report.dto.SalesProtocol.*;
public interface ISalesReportService {
    Report insert(Generate request);
    Page select(Long shopId, int page, int pageSize);
    Detail selectById(String id);
    Export selectExport(String id);
}
