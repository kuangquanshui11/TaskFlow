package com.taskflow.scheduler.history.service;

import java.util.Map;

public interface IHistoryArchiveService {
    Map<String, Integer> runBatch(boolean preview);
}
