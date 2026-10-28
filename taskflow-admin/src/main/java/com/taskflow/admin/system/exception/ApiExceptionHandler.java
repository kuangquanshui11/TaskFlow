package com.taskflow.admin.system.exception;

import java.util.LinkedHashMap;
import java.util.Map;

import com.taskflow.admin.job.exception.JobException;
import com.taskflow.admin.system.dto.ApiResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {
    @ExceptionHandler(com.taskflow.admin.access.exception.AccessSessionException.class)
    public ResponseEntity<Object> handleSession(com.taskflow.admin.access.exception.AccessSessionException exception) {
        return ResponseEntity.status(exception.status()).body(ApiResponse.failure(exception.code(), exception.getMessage(), null));
    }
    @ExceptionHandler(org.springframework.security.access.AccessDeniedException.class)
    public ResponseEntity<Object> handleAccessDenied(org.springframework.security.access.AccessDeniedException exception) {
        return ResponseEntity.status(403).body(ApiResponse.failure("ACCESS_DENIED", "当前账号没有此操作权限", null));
    }
    private static final Logger LOG = LoggerFactory.getLogger(ApiExceptionHandler.class);
    @ExceptionHandler(com.taskflow.admin.node.exception.WorkerNodeException.class)
    public ResponseEntity<Object> handleNode(com.taskflow.admin.node.exception.WorkerNodeException exception) {
        return ResponseEntity.status(exception.status()).body(ApiResponse.failure(exception.code(),exception.getMessage(),null));
    }

    @ExceptionHandler(com.taskflow.admin.jobsync.exception.PlanQueryException.class)
    public ResponseEntity<Object> handlePlanQuery(com.taskflow.admin.jobsync.exception.PlanQueryException exception) {
        return ResponseEntity.status(exception.status())
                .body(ApiResponse.failure(exception.code(), exception.getMessage(), null));
    }

    @ExceptionHandler(com.taskflow.admin.run.exception.RunException.class)
    public ResponseEntity<Object> handleRun(com.taskflow.admin.run.exception.RunException exception) {
        return ResponseEntity.status(exception.status())
                .body(ApiResponse.failure(exception.code(), exception.getMessage(), null));
    }

    @ExceptionHandler(JobException.class)
    public ResponseEntity<Object> handleJob(JobException exception) {
        HttpStatus status = switch (exception.kind()) {
            case INVALID -> HttpStatus.BAD_REQUEST;
            case NOT_FOUND -> HttpStatus.NOT_FOUND;
            case CONFLICT -> HttpStatus.CONFLICT;
        };
        String code = switch (exception.kind()) {
            case INVALID -> "INVALID_ARGUMENT";
            case NOT_FOUND -> "JOB_NOT_FOUND";
            case CONFLICT -> "JOB_VERSION_CONFLICT";
        };
        return ResponseEntity.status(status)
                .body(ApiResponse.failure(code, exception.getMessage(), null));
    }

    @ExceptionHandler({CannotGetJdbcConnectionException.class, CannotCreateTransactionException.class,
            TransientDataAccessException.class})
    public ResponseEntity<Object> handleUnavailable(Exception exception) {
        LOG.warn("Database temporarily unavailable", exception);
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(ApiResponse.failure("DATABASE_UNAVAILABLE", "数据库暂时不可用，请稍后重试", null));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Object> handleUnexpected(Exception exception) {
        LOG.error("Unexpected request failure", exception);
        return ResponseEntity.internalServerError()
                .body(ApiResponse.failure("INTERNAL_ERROR", "服务内部错误，请使用 requestId 排查", null));
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException exception,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        Map<String, String> errors = new LinkedHashMap<>();
        exception.getBindingResult().getFieldErrors()
                .forEach(error -> errors.putIfAbsent(error.getField(), error.getDefaultMessage()));
        return new ResponseEntity<>(ApiResponse.failure("INVALID_ARGUMENT", "请求参数校验失败", errors),
                headers, status);
    }

    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(HttpMessageNotReadableException exception,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        return new ResponseEntity<>(ApiResponse.failure("INVALID_ARGUMENT",
                "请求体必须是合法 JSON，字段类型和枚举值必须正确", null), headers, status);
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception exception, Object body,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        String code = status.value() == 404 ? "ENDPOINT_NOT_FOUND" : "INVALID_REQUEST";
        String message = switch (status.value()) {
            case 404 -> "接口不存在";
            case 405 -> "请求方法不支持";
            case 415 -> "请求需要使用 application/json";
            default -> "请求参数或格式不正确";
        };
        return new ResponseEntity<>(ApiResponse.failure(code, message, null), headers, status);
    }
}
