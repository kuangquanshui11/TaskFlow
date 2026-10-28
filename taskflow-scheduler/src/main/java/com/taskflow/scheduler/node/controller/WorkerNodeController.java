package com.taskflow.scheduler.node.controller;

import com.taskflow.scheduler.node.dto.WorkerNodeProtocol.*;
import com.taskflow.scheduler.node.service.IWorkerNodeService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/internal/v1/workers")
public class WorkerNodeController {
    private final IWorkerNodeService nodes;
    public WorkerNodeController(IWorkerNodeService nodes) { this.nodes=nodes; }
    @PostMapping("/insert") public Acknowledgement register(@Valid @RequestBody Registration request) { return nodes.register(request); }
    @PostMapping("/updateHeartbeat") public Acknowledgement heartbeat(@Valid @RequestBody Heartbeat request) { return nodes.heartbeat(request); }
    @GetMapping("/select") public Page select(@RequestParam(defaultValue="") String keyword,@RequestParam(defaultValue="ALL") String state,
            @RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="10") int pageSize) { return nodes.select(keyword,state,page,pageSize); }
    @GetMapping("/selectById") public View selectById(@RequestParam String workerId) { return nodes.selectById(workerId); }
}
