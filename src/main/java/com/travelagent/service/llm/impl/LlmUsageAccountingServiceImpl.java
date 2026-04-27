package com.travelagent.service.llm.impl;

import com.travelagent.exception.QuotaExhaustedException;
import com.travelagent.mapper.TaskMapper;
import com.travelagent.mapper.UserMapper;
import com.travelagent.model.entity.Task;
import com.travelagent.model.entity.User;
import com.travelagent.service.llm.LlmUsageAccountingService;
import com.travelagent.service.user.QuotaService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class LlmUsageAccountingServiceImpl implements LlmUsageAccountingService {

    private static final Logger log = LoggerFactory.getLogger(LlmUsageAccountingServiceImpl.class);

    @Autowired private TaskMapper taskMapper;
    @Autowired private UserMapper userMapper;
    @Autowired private QuotaService quotaService;

    /**
     * 处理recordUsage。
     * @param taskId 任务ID
     * @param userId 用户ID
     * @param tokens t ok en s 参数
     * @return 返回处理结果。
     */
    @Override
    public int recordUsage(Long taskId, Long userId, int tokens) {
        return doRecordUsage(taskId, userId, tokens, true);
    }

    /**
     * 处理recordUsageLenient。
     * @param taskId 任务ID
     * @param userId 用户ID
     * @param tokens t ok en s 参数
     * @return 返回处理结果。
     */
    @Override
    public int recordUsageLenient(Long taskId, Long userId, int tokens) {
        try {
            return doRecordUsage(taskId, userId, tokens, false);
        } catch (Exception e) {
            log.warn("[LlmUsageAccounting] Lenient usage recording failed for taskId={}, userId={}: {}",
                    taskId, userId, e.getMessage());
            return 0;
        }
    }

    /**
     * 处理doRecordUsage。
     * @param taskId 任务ID
     * @param userId 用户ID
     * @param tokens t ok en s 参数
     * @param strictQuota s tr ic tQ uo ta 参数
     * @return 返回处理结果。
     */
    private int doRecordUsage(Long taskId, Long userId, int tokens, boolean strictQuota) {
        if (taskId == null || tokens <= 0) {
            return 0;
        }

        Task task = taskMapper.findById(taskId);
        if (task == null) {
            return 0;
        }

        Long effectiveUserId = userId != null ? userId : task.getUserId();
        if (effectiveUserId == null) {
            return task.getTotalTokensUsed() == null ? 0 : task.getTotalTokensUsed();
        }

        User user = userMapper.findById(effectiveUserId);
        int userLevel = user != null && user.getUserLevel() != null ? user.getUserLevel() : 1;

        QuotaExhaustedException quotaException = null;
        try {
            quotaService.debitTokens(effectiveUserId, userLevel, tokens);
        } catch (QuotaExhaustedException e) {
            quotaException = e;
        }

        int updatedTotal = (task.getTotalTokensUsed() == null ? 0 : task.getTotalTokensUsed()) + tokens;
        task.setTotalTokensUsed(updatedTotal);
        taskMapper.update(task);

        if (quotaException != null && strictQuota) {
            throw quotaException;
        }
        return updatedTotal;
    }
}
