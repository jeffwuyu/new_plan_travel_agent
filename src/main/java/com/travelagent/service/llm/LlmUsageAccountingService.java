package com.travelagent.service.llm;

public interface LlmUsageAccountingService {

    int recordUsage(Long taskId, Long userId, int tokens);

    int recordUsageLenient(Long taskId, Long userId, int tokens);
}
