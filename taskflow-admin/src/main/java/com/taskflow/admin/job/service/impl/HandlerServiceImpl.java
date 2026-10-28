package com.taskflow.admin.job.service.impl;

import java.util.Arrays;
import java.util.List;

import com.taskflow.admin.job.service.IHandlerService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class HandlerServiceImpl implements IHandlerService {
    private final List<String> keys;

    public HandlerServiceImpl(@Value("${taskflow.jobs.allowed-handler-keys}") String configuredKeys) {
        keys = Arrays.stream(configuredKeys.split(","))
                .map(String::strip).filter(key -> !key.isEmpty()).distinct().sorted().toList();
        if (keys.stream().anyMatch(key -> !key.matches("[A-Za-z][A-Za-z0-9_.-]{0,99}"))) {
            throw new IllegalStateException("处理器白名单包含不合法的标识");
        }
    }

    @Override
    public List<String> select() {
        return keys;
    }
}
