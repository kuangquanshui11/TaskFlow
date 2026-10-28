package com.taskflow.gateway.access.controller;

import java.util.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ServerWebExchange;

@RestController
public class GatewayFallbackController {
    @RequestMapping("/fallback/unavailable")
    public ResponseEntity<Map<String,Object>> unavailable(ServerWebExchange exchange) {
        var result=new LinkedHashMap<String,Object>();
        result.put("code","GATEWAY_UNAVAILABLE");result.put("message","管理服务暂时不可用，请稍后重试");result.put("data",null);
        result.put("requestId",exchange.getResponse().getHeaders().getFirst("X-Request-Id"));
        return ResponseEntity.status(503).body(result);
    }
}
