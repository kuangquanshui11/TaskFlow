package com.taskflow.admin.run.dto;

import java.util.UUID;
import jakarta.validation.constraints.*;

public final class RunRequests {
    private RunRequests() { }
    public record Insert(@Positive long jobId, @Positive long configVersion, @NotNull UUID requestKey) { }
}
