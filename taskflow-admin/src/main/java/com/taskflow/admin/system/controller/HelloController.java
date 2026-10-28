package com.taskflow.admin.system.controller;

import com.taskflow.admin.system.service.ISystemService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class HelloController {
    private final ISystemService system;

    public HelloController(ISystemService system) {
        this.system = system;
    }

    @GetMapping("/hello")
    public String hello() {
        return system.hello();
    }
}
