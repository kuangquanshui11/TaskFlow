package com.taskflow.admin.node.controller;

import com.taskflow.admin.node.dto.WorkerNodeResponse.*;
import com.taskflow.admin.node.service.IWorkerNodeService;
import com.taskflow.admin.system.dto.ApiResponse;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/workers")
public class WorkerNodeController {
    private final IWorkerNodeService nodes;
    public WorkerNodeController(IWorkerNodeService nodes) { this.nodes=nodes; }
    @GetMapping("/select") public ApiResponse<Page> select(@RequestParam(defaultValue="") String keyword,@RequestParam(defaultValue="ALL") String state,
            @RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="10") int pageSize) { return ApiResponse.success(nodes.select(keyword,state,page,pageSize)); }
    @GetMapping("/selectById") public ApiResponse<View> selectById(@RequestParam String workerId) { return ApiResponse.success(nodes.selectById(workerId)); }
}
