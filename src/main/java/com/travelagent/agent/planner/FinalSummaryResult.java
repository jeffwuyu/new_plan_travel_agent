package com.travelagent.agent.planner;

import java.util.List;

/**
 * 初始化FinalSummaryResult 实例。
 * @param title t it le 参数
 * @param summary s um ma ry 参数
 * @param steps 步骤列表
 */
public record FinalSummaryResult(
        String title,
        String summary,
        List<StepSummary> steps
) {

    /**
     * 处理StepSummary。
     * @param stepOrder s te pO rd er 参数
     * @param estimatedDurationMin e st im at ed Du ra ti on Mi n 参数
     * @param llmDescription l lm De sc ri pt io n 参数
     * @return 返回处理结果。
     */
    public record StepSummary(
            int stepOrder,
            int estimatedDurationMin,
            String llmDescription
    ) {}
}
