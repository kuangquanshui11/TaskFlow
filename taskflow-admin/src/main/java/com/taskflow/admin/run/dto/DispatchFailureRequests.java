package com.taskflow.admin.run.dto;

import java.util.UUID;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public final class DispatchFailureRequests {
    private DispatchFailureRequests() { }

    public record Disposition(@NotNull UUID requestKey,
            @NotBlank @Pattern(regexp="DEAD_LETTER|OUTBOX|RESULT_REPORT") String source,
            @NotBlank @Pattern(regexp="[a-f0-9-]{36,64}") String id,
            @NotBlank @Pattern(regexp="ACKNOWLEDGE|RESOLVE") String action,
            @NotBlank @Size(max=250) String conclusion, @NotBlank @Size(max=250) String evidence) { }
}
