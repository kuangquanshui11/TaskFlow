package com.taskflow.worker.dispatch.dto;

import java.util.UUID;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import com.taskflow.worker.execution.dto.ExecutionProtocol.Dispatch;

public record DispatchEvent(@Min(1) @Max(1) int version, @NotNull UUID eventId,
        @NotNull @Valid Dispatch command) { }
