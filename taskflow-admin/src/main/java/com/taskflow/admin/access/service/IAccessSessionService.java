package com.taskflow.admin.access.service;

import org.springframework.security.oauth2.jwt.Jwt;

public interface IAccessSessionService {
    void insert(Jwt jwt);
    void validateAndTouch(Jwt jwt);
    void update(Jwt jwt, String previousToken);
    void disable(Jwt jwt);
}
