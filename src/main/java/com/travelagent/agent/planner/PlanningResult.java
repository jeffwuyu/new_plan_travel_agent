package com.travelagent.agent.planner;

import com.travelagent.model.dto.LocationCandidateItem;
import com.travelagent.model.dto.SelectionOptionItem;

import java.util.List;
import java.util.Map;

/**
 * 初始化PlanningResult 实例。
 * @param attractionName 景点名称
 * @param totalTokens t ot al To ke ns 参数
 * @param recommendationCandidates r ec om me nd at io nC an di da te s 参数
 * @param pendingInputType 待处理输入类型
 * @param selectionStage 选择阶段
 * @param selectedBranchType s el ec te dB ra nc hT yp e 参数
 * @param selectionOptions s el ec ti on Op ti on s 参数
 * @param currentContext c ur re nt Co nt ex t 参数
 * @param weatherContext w ea th er Co nt ex t 参数
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
     * 处理forAttraction。
     * @param attractionName 景点名称
     * @param totalTokens t ot al To ke ns 参数
     * @return 返回处理结果。
     */
    public static PlanningResult forAttraction(String attractionName, int totalTokens) {
        return new PlanningResult(attractionName, totalTokens, List.of(), null, null, null,
                List.of(), Map.of(), Map.of());
    }

    /**
     * 处理forBranchSelection。
     * @param selectionOptions s el ec ti on Op ti on s 参数
     * @param currentContext c ur re nt Co nt ex t 参数
     * @param weatherContext w ea th er Co nt ex t 参数
     * @return 返回处理结果。
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
     * 处理forCandidates。
     * @param recommendationCandidates r ec om me nd at io nC an di da te s 参数
     * @param totalTokens t ot al To ke ns 参数
     * @param pendingInputType 待处理输入类型
     * @param selectionStage 选择阶段
     * @param selectedBranchType s el ec te dB ra nc hT yp e 参数
     * @param currentContext c ur re nt Co nt ex t 参数
     * @param weatherContext w ea th er Co nt ex t 参数
     * @return 返回处理结果。
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
     * 判断requiresUserSelection。
     * @return 是否满足当前条件。
     */
    public boolean requiresUserSelection() {
        return pendingInputType != null && !pendingInputType.isBlank();
    }
}
