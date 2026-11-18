package com.taskflow.admin.history.service.impl;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import com.taskflow.admin.history.config.HistoryArchiveProperties;
import com.taskflow.admin.history.mapper.HistoryArchiveMapper;
import com.taskflow.admin.history.service.IHistoryArchiveService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class HistoryArchiveServiceImpl implements IHistoryArchiveService {
    private final HistoryArchiveMapper mapper;
    private final HistoryArchiveProperties properties;

    public HistoryArchiveServiceImpl(HistoryArchiveMapper mapper, HistoryArchiveProperties properties) {
        this.mapper = mapper;
        this.properties = properties;
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Map<String, Integer> runBatch(boolean preview) {
        var cutoff = Instant.now().minus(properties.retentionDays(), ChronoUnit.DAYS);
        if (!preview) mapper.lockBatch();
        return mapper.process(cutoff, properties.batchSize(), preview);
    }
}
