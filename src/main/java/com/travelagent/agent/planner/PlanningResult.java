package com.travelagent.agent.planner;

import com.travelagent.model.dto.LocationCandidateItem;
import com.travelagent.model.dto.SelectionOptionItem;

import java.util.List;
import java.util.Map;

/**
 * 单轮 Markov 规划结果，承载直接命中的景点、候选选择数据和本轮 LLM token 消耗。
 *
 * @param attractionName 可直接执行工具调用的景点名称
 * @param totalTokens 本轮规划消耗的 token 数
 * @param recommendationCandidates 需要用户或自动策略确认的景点候选
 * @param pendingInputType 前端待输入类型，为空表示无需用户选择
 * @param selectionStage 当前选择阶段
 * @param selectedBranchType 已选分支类型，用于多分支推荐上下文
 * @param selectionOptions 分支选择项
 * @param currentContext 前端展示和恢复规划所需的当前上下文
 * @param weatherContext 天气相关上下文
 */
public record PlanningResult(String attractionName,
                             int totalTokens,
                             List<LocationCandidateItem> recommendationCandidates,
                             String pendingInputType,
                             String selectionStage,
                             String selectedBranchType,
                             List<SelectionOptionItem> selectionOptions,
                             Map<String, Object> currentContext,
                             Map<String, Object> weatherContext) {

    /**
     * 创建一个无需用户选择、可直接执行工具调用的规划结果。
     *
     * @param attractionName 景点名称
     * @param totalTokens 本轮规划消耗的 token 数
     * @return 直接景点规划结果
     */
    public static PlanningResult forAttraction(String attractionName, int totalTokens) {
        return new PlanningResult(attractionName, totalTokens, List.of(), null, null, null,
                List.of(), Map.of(), Map.of());
    }

    /**
     * 创建需要用户先选择推荐分支的规划结果。
     *
     * @param selectionOptions 分支选择项
     * @param currentContext 当前规划上下文
     * @param weatherContext 天气上下文
     * @return 分支选择规划结果
     */
    public static PlanningResult forBranchSelection(List<SelectionOptionItem> selectionOptions,
                                                    Map<String, Object> currentContext,
                                                    Map<String, Object> weatherContext) {
        return new PlanningResult(null, 0, List.of(), "selection_branch", "branch_selection", null,
                selectionOptions == null ? List.of() : selectionOptions,
                currentContext == null ? Map.of() : currentContext,
                weatherContext == null ? Map.of() : weatherContext);
    }

    /**
     * 创建需要在景点候选中选择一个目标的规划结果。
     *
     * @param recommendationCandidates 推荐候选列表
     * @param totalTokens 本轮规划消耗的 token 数
     * @param pendingInputType 前端待输入类型
     * @param selectionStage 选择阶段
     * @param selectedBranchType 已选分支类型
     * @param currentContext 当前规划上下文
     * @param weatherContext 天气上下文
     * @return 候选选择规划结果
     */
    public static PlanningResult forCandidates(List<LocationCandidateItem> recommendationCandidates,
                                               int totalTokens,
                                               String pendingInputType,
                                               String selectionStage,
                                               String selectedBranchType,
                                               Map<String, Object> currentContext,
                                               Map<String, Object> weatherContext) {
        return new PlanningResult(null, totalTokens,
                recommendationCandidates == null ? List.of() : recommendationCandidates,
                pendingInputType, selectionStage, selectedBranchType, List.of(),
                currentContext == null ? Map.of() : currentContext,
                weatherContext == null ? Map.of() : weatherContext);
    }

    /**
     * 判断本轮规划是否需要用户或自动选择策略确认。
     *
     * @return 需要选择时返回 true
     */
    public boolean requiresUserSelection() {
        return pendingInputType != null && !pendingInputType.isBlank();
    }
}
