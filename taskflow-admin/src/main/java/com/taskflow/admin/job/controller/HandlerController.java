package com.taskflow.admin.job.controller;

import java.util.List;

import com.taskflow.admin.job.service.IHandlerService;
import com.taskflow.admin.system.dto.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/job-handlers")
public class HandlerController {
    private final IHandlerService catalog;

    public HandlerController(IHandlerService catalog) {
        this.catalog = catalog;
    }

    @GetMapping("/select")
    public ApiResponse<List<String>> select() {
        return ApiResponse.success(catalog.select());
    }
}
