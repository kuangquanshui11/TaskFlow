package com.taskflow.admin.run.dto;

import java.util.UUID;
import jakarta.validation.constraints.*;

public final class RunRequests {
    private RunRequests() { }
    public record Insert(@Positive long jobId, @Positive long configVersion, @NotNull UUID requestKey) { }
    public record Rerun(@NotNull UUID sourceRunId, @Positive long configVersion, @NotNull UUID requestKey) { }
    public record RerunConfiguration(String name, String handlerKey, String configVersion, boolean enabled) { }
}
