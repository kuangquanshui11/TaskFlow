package com.taskflow.admin.access.controller;

import com.taskflow.admin.access.dto.AccessResponses.*;
import com.taskflow.admin.access.service.IAccessService;
import com.taskflow.admin.system.dto.ApiResponse;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/access")
public class AccessController {
    private final IAccessService access;
    private final com.taskflow.admin.access.service.IAccessSessionService sessions;
    public AccessController(IAccessService access, com.taskflow.admin.access.service.IAccessSessionService sessions) {
        this.access = access; this.sessions = sessions;
    }
    @PostMapping("/insert")
    public ApiResponse<Void> insert(@AuthenticationPrincipal Jwt jwt) { sessions.insert(jwt); return ApiResponse.success(null); }
    @PostMapping("/update")
    public ApiResponse<Void> update(@AuthenticationPrincipal Jwt jwt, @RequestBody Rotation input) {
        sessions.update(jwt, input.previousToken()); return ApiResponse.success(null);
    }
    @PostMapping("/disable")
    public ApiResponse<Void> disable(@AuthenticationPrincipal Jwt jwt) { sessions.disable(jwt); return ApiResponse.success(null); }
    public record Rotation(String previousToken) {}
    @GetMapping("/selectConfiguration")
    public ApiResponse<Configuration> selectConfiguration() { return ApiResponse.success(access.selectConfiguration()); }
    @GetMapping("/selectCurrent")
    public ApiResponse<Current> selectCurrent(@AuthenticationPrincipal Jwt jwt) { return ApiResponse.success(access.selectCurrent(jwt)); }
}
