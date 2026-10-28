package com.taskflow.admin.access.mapper;

import java.time.Duration;

public interface AccessSessionMapper {
    boolean create(String key, String token, long authenticatedAt, Duration idleTimeout, Duration grace, boolean allowNew);
    boolean validateAndTouch(String key, String token, Duration idleTimeout);
    boolean rotate(String key, String previousToken, String token, Duration idleTimeout, Duration grace);
    void revoke(String key, Duration idleTimeout);
}
