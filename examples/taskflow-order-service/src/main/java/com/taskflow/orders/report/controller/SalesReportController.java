package com.taskflow.orders.report.controller;

import com.taskflow.orders.report.dto.SalesProtocol.*;
import com.taskflow.orders.report.service.ISalesReportService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
public class SalesReportController {
    private final ISalesReportService reports;
    public SalesReportController(ISalesReportService reports) { this.reports=reports; }
    @PostMapping("/internal/v1/sales-reports/insert")
    public Report insert(@Valid @RequestBody Generate request) { return reports.insert(request); }
    @GetMapping("/internal/v1/sales-reports/select")
    public Page select(@RequestParam(required=false) Long shopId,@RequestParam(defaultValue="1") int page,
                       @RequestParam(defaultValue="20") int pageSize) { return reports.select(shopId,page,pageSize); }
    @GetMapping("/internal/v1/sales-reports/selectById")
    public Detail detail(@RequestParam String id) { return reports.selectById(id); }
    @GetMapping("/internal/v1/sales-reports/selectExport")
    public Export export(@RequestParam String id) { return reports.selectExport(id); }
}
