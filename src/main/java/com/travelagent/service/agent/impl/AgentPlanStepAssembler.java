package com.travelagent.service.agent.impl;

import com.travelagent.agent.context.CompletedStep;
import com.travelagent.agent.planner.FinalSummaryResult;
import com.travelagent.agent.tools.TrafficTimeTool;
import com.travelagent.agent.tools.WeatherTool;
import com.travelagent.model.entity.PlanStep;
import com.travelagent.util.JsonUtil;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Agent 计划步骤组装器。
 *
 * <p>该组件把 checkpoint 中的已完成步骤和 LLM 最终摘要转换为 `plan_steps` 实体，
 * 避免 Agent 主控服务同时承担落库模型拼装职责。</p>
 */
@Component
public class AgentPlanStepAssembler {

    private final JsonUtil jsonUtil;

    /**
     * 创建计划步骤组装器。
     *
     * @param jsonUtil JSON 工具，用于序列化路线几何
     */
    public AgentPlanStepAssembler(JsonUtil jsonUtil) {
        this.jsonUtil = jsonUtil;
    }

    /**
     * 构建计划步骤实体列表。
     *
     * @param planId 计划 ID
     * @param completedSteps checkpoint 中已完成的规划步骤
     * @param stepSummaries LLM 最终摘要中的步骤说明
     * @return 可写入数据库的计划步骤列表
     */
    public List<PlanStep> buildPlanSteps(Long planId,
                                         List<CompletedStep> completedSteps,
                                         List<FinalSummaryResult.StepSummary> stepSummaries) {
        Map<Integer, FinalSummaryResult.StepSummary> summaryByOrder = new LinkedHashMap<>();
        for (FinalSummaryResult.StepSummary ss : stepSummaries) {
            summaryByOrder.put(ss.stepOrder(), ss);
        }

        List<PlanStep> steps = new ArrayList<>();
        for (CompletedStep completedStep : completedSteps) {
            PlanStep planStep = buildBaseStep(planId, completedStep);
            applySummary(planStep, completedStep, summaryByOrder.get(completedStep.getStepIndex()));
            applyToolResults(planStep, completedStep.getToolCallResults(), completedStep);
            steps.add(planStep);
        }
        return steps;
    }

    /**
     * 构建计划步骤基础字段。
     *
     * @param planId 计划 ID
     * @param completedStep 已完成步骤
     * @return 基础步骤实体
     */
    private PlanStep buildBaseStep(Long planId, CompletedStep completedStep) {
        PlanStep planStep = new PlanStep();
        planStep.setPlanId(planId);
        planStep.setStepOrder(completedStep.getStepIndex());
        planStep.setDayNumber(completedStep.getDayNumber());
        planStep.setAttractionName(completedStep.getAttractionName());
        planStep.setLatitude(completedStep.getLat() != null ? BigDecimal.valueOf(completedStep.getLat()) : null);
        planStep.setLongitude(completedStep.getLng() != null ? BigDecimal.valueOf(completedStep.getLng()) : null);
        planStep.setPlannedStartTime(completedStep.getPlannedStartTime());
        planStep.setPlannedEndTime(completedStep.getPlannedEndTime());
        planStep.setTravelTimeToDestinationMin(completedStep.getTravelTimeToDestinationMin());
        return planStep;
    }

    /**
     * 应用 LLM 最终摘要中的步骤说明。
     *
     * @param planStep 计划步骤实体
     * @param completedStep 已完成步骤
     * @param summary 步骤摘要，可为空
     */
    private void applySummary(PlanStep planStep,
                              CompletedStep completedStep,
                              FinalSummaryResult.StepSummary summary) {
        planStep.setEstimatedDurationMin(
                summary != null ? summary.estimatedDurationMin() : completedStep.getEstimatedVisitDurationMin());
        planStep.setLlmDescription(summary != null ? summary.llmDescription() : null);
    }

    /**
     * 应用工具结果中的交通和天气字段。
     *
     * @param planStep 计划步骤实体
     * @param toolResults 工具调用结果
     * @param completedStep 已完成步骤
     */
    private void applyToolResults(PlanStep planStep,
                                  Map<String, Object> toolResults,
                                  CompletedStep completedStep) {
        if (toolResults == null) {
            return;
        }
        applyTrafficResult(planStep, toolResults, completedStep);
        applyWeatherResult(planStep, toolResults);
    }

    /**
     * 应用交通工具结果。
     *
     * @param planStep 计划步骤实体
     * @param toolResults 工具调用结果
     * @param completedStep 已完成步骤
     */
    private void applyTrafficResult(PlanStep planStep,
                                    Map<String, Object> toolResults,
                                    CompletedStep completedStep) {
        Map<?, ?> traffic = (Map<?, ?>) toolResults.get(TrafficTimeTool.NAME);
        if (!isConsumableToolResult(traffic)) {
            planStep.setTrafficTimeFromPrev(completedStep.getTrafficTimeFromPrevMin());
            return;
        }
        if (traffic != null && traffic.get("durationMin") != null) {
            planStep.setTrafficTimeFromPrev(((Number) traffic.get("durationMin")).intValue());
        } else {
            planStep.setTrafficTimeFromPrev(completedStep.getTrafficTimeFromPrevMin());
        }
        if (traffic == null) {
            return;
        }

        Object routeMode = traffic.get("routeMode");
        if (routeMode != null) {
            planStep.setTrafficModeFromPrev(routeMode.toString());
        }
        Object routeSummary = traffic.get("routeSummary");
        if (routeSummary != null) {
            planStep.setSelectedRouteSummaryFromPrev(routeSummary.toString());
        }
        if (jsonUtil != null) {
            Map<String, Object> routeGeometry = new LinkedHashMap<>();
            routeGeometry.put("routeMode", routeMode == null ? null : routeMode.toString());
            routeGeometry.put("durationMin", traffic.get("durationMin"));
            routeGeometry.put("distanceMeters", traffic.get("distanceMeters"));
            routeGeometry.put("polyline", traffic.get("polyline"));
            routeGeometry.put("rawPolyline", traffic.get("rawPolyline"));
            Object fallback = traffic.containsKey("fallback") ? traffic.get("fallback") : Boolean.FALSE;
            routeGeometry.put("fallback", fallback);
            planStep.setSelectedRouteGeometryJson(jsonUtil.toJson(routeGeometry));
        }
    }

    /**
     * 应用天气工具结果。
     *
     * @param planStep 计划步骤实体
     * @param toolResults 工具调用结果
     */
    private void applyWeatherResult(PlanStep planStep, Map<String, Object> toolResults) {
        Map<?, ?> weather = (Map<?, ?>) toolResults.get(WeatherTool.NAME);
        if (!isConsumableToolResult(weather)) {
            return;
        }
        Object weatherValue = weather.get("weather");
        Object temperatureValue = weather.get("temperature");
        String weatherText = weatherValue == null ? "" : weatherValue.toString().trim();
        String temperatureText = temperatureValue == null ? "" : temperatureValue.toString().trim();
        planStep.setWeatherNote((weatherText + " " + temperatureText + " C").trim());
    }

    private boolean isConsumableToolResult(Map<?, ?> result) {
        if (result == null || Boolean.FALSE.equals(result.get("available"))) {
            return false;
        }
        Object validation = result.get("resultValidation");
        if (validation instanceof Map<?, ?> validationMap && Boolean.FALSE.equals(validationMap.get("valid"))) {
            return false;
        }
        return true;
    }
}
