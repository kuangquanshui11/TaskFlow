package com.taskflow.scheduler.execution.exception;

import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.CannotCreateTransactionException;

@RestControllerAdvice
public class ExecutionExceptionHandler {
    @ExceptionHandler(com.taskflow.scheduler.node.exception.WorkerNodeException.class)
    ResponseEntity<?> node(com.taskflow.scheduler.node.exception.WorkerNodeException error) {
        return ResponseEntity.status(error.status()).body(Map.of("code",error.code(),"message",error.getMessage()));
    }
    @ExceptionHandler(RunException.class)
    ResponseEntity<?> business(RunException error) {
        return ResponseEntity.status(error.status()).body(Map.of("code", error.code(), "message", error.getMessage()));
    }
    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class,
            IllegalArgumentException.class, jakarta.validation.ConstraintViolationException.class,
            org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class})
    ResponseEntity<?> invalid(Exception error) {
        return ResponseEntity.badRequest().body(Map.of("code", "INVALID_ARGUMENT", "message", "执行请求参数不合法"));
    }
    @ExceptionHandler({DataAccessException.class, CannotCreateTransactionException.class})
    ResponseEntity<?> unavailable(Exception error) {
        return ResponseEntity.status(503).body(Map.of("code", "DATABASE_UNAVAILABLE", "message", "运行存储暂时不可用"));
    }
    @ExceptionHandler(Exception.class)
    ResponseEntity<?> unexpected(Exception error) {
        if (error instanceof org.springframework.web.ErrorResponse response)
            return ResponseEntity.status(response.getStatusCode()).body(Map.of("code", "INVALID_REQUEST", "message", "接口或请求方法不可用"));
        org.slf4j.LoggerFactory.getLogger(getClass()).error("Internal execution error: {}", error.getClass().getSimpleName());
        return ResponseEntity.internalServerError().body(Map.of("code", "INTERNAL_ERROR", "message", "执行服务内部错误"));
    }
}
