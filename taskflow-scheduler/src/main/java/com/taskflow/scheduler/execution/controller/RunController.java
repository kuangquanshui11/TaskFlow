package com.taskflow.scheduler.execution.controller;

import java.util.UUID;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import com.taskflow.scheduler.execution.dto.ExecutionProtocol.Create;
import com.taskflow.scheduler.execution.model.RunRecords.*;
import com.taskflow.scheduler.execution.service.IRunService;

@RestController
@RequestMapping("/internal/v1/runs")
public class RunController {
    private final IRunService runs;
    public RunController(IRunService runs) { this.runs = runs; }
    @PostMapping("/insert") @ResponseStatus(HttpStatus.ACCEPTED)
    public RunView insert(@Valid @RequestBody Create request) { return runs.insert(request); }
    @GetMapping("/selectById")
    public RunView selectById(@RequestParam UUID id) { return runs.selectById(id); }
    @GetMapping("/selectByRequestKey")
    public RunView selectByRequestKey(@RequestParam UUID requestKey) { return runs.selectByRequestKey(requestKey); }
    @GetMapping("/select")
    public Page select(@RequestParam long jobId, @RequestParam(defaultValue="1") int page,
                       @RequestParam(defaultValue="10") int pageSize) { return runs.select(jobId, page, pageSize); }
}
