package com.taskflow.admin.system.service.impl;

import com.taskflow.admin.system.service.ISystemService;
import org.springframework.stereotype.Service;

@Service
public class SystemServiceImpl implements ISystemService {
    @Override
    public String hello() {
        return "TaskFlow 已启动";
    }
}
