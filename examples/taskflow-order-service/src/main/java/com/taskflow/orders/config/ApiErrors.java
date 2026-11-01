package com.taskflow.orders.config;

import java.util.Map;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice
public class ApiErrors {
    @ExceptionHandler(org.springframework.web.server.ResponseStatusException.class)
    ResponseEntity<?> business(org.springframework.web.server.ResponseStatusException error) {
        return ResponseEntity.status(error.getStatusCode()).body(Map.of("code",error.getReason()==null?"BUSINESS_ERROR":error.getReason()));
    }
    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<?> invalid(IllegalArgumentException error) { return ResponseEntity.badRequest().body(Map.of("code","INVALID_ARGUMENT")); }
    @ExceptionHandler(DuplicateKeyException.class)
    ResponseEntity<?> duplicate(DuplicateKeyException error) { return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("code","ALREADY_EXISTS")); }
}
