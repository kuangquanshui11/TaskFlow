package com.taskflow.admin.sales.service;
import com.taskflow.admin.sales.dto.SalesResponse.*;
public interface ISalesReportService {
    Page select(Long shopId,int page,int pageSize);
    Detail selectById(String id);
    Export selectExport(String id);
}
