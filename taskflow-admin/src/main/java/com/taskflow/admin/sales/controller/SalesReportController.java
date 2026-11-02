package com.taskflow.admin.sales.controller;
import com.taskflow.admin.sales.dto.SalesResponse.*;
import com.taskflow.admin.sales.service.ISalesReportService;
import com.taskflow.admin.system.dto.ApiResponse;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/sales-reports")
public class SalesReportController {
    private final ISalesReportService reports;
    public SalesReportController(ISalesReportService reports) { this.reports=reports; }
    @GetMapping("/select") public ApiResponse<Page> select(@RequestParam(required=false) Long shopId,
            @RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int pageSize) {
        return ApiResponse.success(reports.select(shopId,page,pageSize));
    }
    @GetMapping("/selectById") public ApiResponse<Detail> detail(@RequestParam String id) { return ApiResponse.success(reports.selectById(id)); }
    @GetMapping("/selectExport") public ApiResponse<Export> export(@RequestParam String id) { return ApiResponse.success(reports.selectExport(id)); }
}
