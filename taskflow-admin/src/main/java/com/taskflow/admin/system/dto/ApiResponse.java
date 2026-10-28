package com.taskflow.admin.system.dto;

import org.slf4j.MDC;

public record ApiResponse<T>(String code, String message, T data, String requestId) {
    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>("OK", "操作成功", data, MDC.get("requestId"));
    }

    public static <T> ApiResponse<T> failure(String code, String message, T details) {
        return new ApiResponse<>(code, message, details, MDC.get("requestId"));
    }
}
