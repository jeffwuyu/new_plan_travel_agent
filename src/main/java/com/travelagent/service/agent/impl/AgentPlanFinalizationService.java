package com.travelagent.service.agent.impl;

import com.travelagent.agent.context.CompletedStep;
import com.travelagent.agent.context.TaskCheckpoint;
import com.travelagent.agent.planner.FinalSummaryResult;
import com.travelagent.agent.planner.MarkovPlanner;
import com.travelagent.exception.AgentException;
import com.travelagent.model.entity.Plan;
import com.travelagent.model.entity.PlanAccommodation;
import com.travelagent.model.entity.PlanStep;
import com.travelagent.model.entity.Task;
import com.travelagent.service.accommodation.AccommodationRecommendationResult;
import com.travelagent.service.accommodation.AccommodationRecommendationService;
import com.travelagent.service.plan.PlanPersistenceService;
import com.travelagent.service.routemap.PlanRouteMapService;
import com.travelagent.service.task.TaskProgressService;
import com.travelagent.util.JsonUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Agent 计划最终化服务，负责生成最终摘要、落库计划步骤、推荐住宿并触发路线图派生产物。
 */
@Component
public class AgentPlanFinalizationService {

    private static final Logger log = LoggerFactory.getLogger(AgentPlanFinalizationService.class);

    private final MarkovPlanner markovPlanner;
    private final PlanPersistenceService planPersistenceService;
    private final AccommodationRecommendationService accommodationRecommendationService;
    private final TaskProgressService taskProgressService;
    private final JsonUtil jsonUtil;

    @Autowired(required = false)
    private PlanRouteMapService planRouteMapService;

    @Autowired
    private AgentPlanStepAssembler planStepAssembler;

    @Value("${route-map.auto-generate-on-plan-complete:true}")
    private boolean autoGenerateRouteMapOnPlanComplete;

    /**
     * 创建 Agent 计划最终化服务。
     *
     * @param markovPlanner 马尔可夫规划器
     * @param planPersistenceService 计划持久化服务
     * @param accommodationRecommendationService 住宿推荐服务
     * @param taskProgressService 任务进度服务
     * @param jsonUtil JSON 工具
     */
    public AgentPlanFinalizationService(MarkovPlanner markovPlanner,
                                        PlanPersistenceService planPersistenceService,
                                        AccommodationRecommendationService accommodationRecommendationService,
                                        TaskProgressService taskProgressService,
                                        JsonUtil jsonUtil) {
        this.markovPlanner = markovPlanner;
        this.planPersistenceService = planPersistenceService;
        this.accommodationRecommendationService = accommodationRecommendationService;
        this.taskProgressService = taskProgressService;
        this.jsonUtil = jsonUtil;
    }

    /**
     * 将 checkpoint 中的规划结果最终化为计划，并触发可选派生产物。
     *
     * @param task 任务实体
     * @param checkpoint 任务 checkpoint
     * @return 最终化结果
     */
    public AgentPlanFinalizationResult finalizePlan(Task task, TaskCheckpoint checkpoint) {
        Plan plan = persistPlan(task, checkpoint);
        triggerRouteMapGeneration(plan.getId(), task.getUserId());
        return new AgentPlanFinalizationResult(plan.getId());
    }

    /**
     * 生成最终计划摘要并写入 plans、plan_steps 和住宿推荐。
     *
     * @param task 任务实体
     * @param checkpoint 任务 checkpoint
     * @return 已落库计划
     */
    private Plan persistPlan(Task task, TaskCheckpoint checkpoint) {
        FinalSummaryResult summary = generateFinalSummary(task, checkpoint);

        Plan plan = new Plan();
        plan.setTaskId(task.getId());
        plan.setUserId(task.getUserId());
        plan.setRegion(checkpoint.getRegion());
        plan.setTotalDays(checkpoint.getPlanningConfig().getTotalDays());
        plan.setStartLocationQuery(checkpoint.getStartLocationQuery());
        plan.setEndLocationQuery(checkpoint.getEndLocationQuery());
        plan.setTripStartTime(checkpoint.getTripStartTime());
        plan.setTripEndTime(checkpoint.getTripEndTime());
        plan.setFullDayStartTime(checkpoint.getPlanningConfig().resolveFullDayStartTime());
        plan.setFullDayEndTime(checkpoint.getPlanningConfig().resolveFullDayEndTime());
        plan.setDestinationBufferMin(checkpoint.getPlanningConfig().getDestinationBufferMin());
        if (summary != null) {
            plan.setTitle(summary.title());
            plan.setSummary(summary.summary());
        } else {
            plan.setTitle(checkpoint.getRegion() + " " + checkpoint.getPlanningConfig().getTotalDays() + "-Day Trip");
            plan.setSummary(checkpoint.getUserIntent());
        }
        checkpoint.setFinalItinerary(plan.getSummary());
        Map<String, Object> finalSummary = new LinkedHashMap<>();
        finalSummary.put("type", "final_summary");
        finalSummary.put("title", plan.getTitle());
        finalSummary.put("summary", plan.getSummary());
        checkpoint.recordIntermediateSummary(finalSummary);
        planPersistenceService.insertPlan(plan);

        List<FinalSummaryResult.StepSummary> stepSummaries = summary != null ? summary.steps() : List.of();
        List<PlanStep> steps = buildPlanSteps(plan.getId(), checkpoint.getCompletedSteps(), stepSummaries);
        planPersistenceService.insertSteps(steps);
        persistAccommodations(task, checkpoint, plan);
        return plan;
    }

    /**
     * 调用 LLM 生成最终摘要，失败时允许回落到 checkpoint 基础摘要。
     *
     * @param task 任务实体
     * @param checkpoint 任务 checkpoint
     * @return 最终摘要，生成失败时返回 null
     */
    private FinalSummaryResult generateFinalSummary(Task task, TaskCheckpoint checkpoint) {
        try {
            return markovPlanner.generateFinalSummary(task, checkpoint, task.getTaskUuid());
        } catch (AgentException e) {
            throw e;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 保存住宿推荐结果和住宿进度事件。
     *
     * @param task 任务实体
     * @param checkpoint 任务 checkpoint
     * @param plan 已落库计划
     */
    private void persistAccommodations(Task task, TaskCheckpoint checkpoint, Plan plan) {
        AccommodationRecommendationResult result = accommodationRecommendationService.recommend(plan.getId(), checkpoint);
        planPersistenceService.updateAccommodationStatus(plan.getId(), result.status(), result.failureReason());
        if (!result.accommodations().isEmpty()) {
            List<PlanAccommodation> accommodations = result.accommodations();
            planPersistenceService.insertAccommodations(accommodations);
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("planId", plan.getId());
        payload.put("status", result.status());
        payload.put("failureReason", result.failureReason());
        payload.put("recommendationCount", result.accommodations().size());
        taskProgressService.recordEvent(task.getTaskUuid(), "ACCOMMODATION", task.getStatus(),
                null, null, "Accommodation recommendation " + result.status(), payload);
    }

    /**
     * 按配置触发每日路线图自动生成。
     *
     * @param planId 计划 ID
     * @param userId 用户 ID
     */
    private void triggerRouteMapGeneration(Long planId, Long userId) {
        if (!autoGenerateRouteMapOnPlanComplete || planRouteMapService == null || planId == null || userId == null) {
            return;
        }
        try {
            planRouteMapService.triggerAutoGenerate(planId, userId);
        } catch (Exception e) {
            log.warn("Route map auto generation failed to start for planId={}: {}", planId, e.getMessage());
        }
    }

    /**
     * 委托步骤组装器构建计划步骤实体。
     *
     * @param planId 计划 ID
     * @param completedSteps 已完成步骤
     * @param stepSummaries LLM 最终摘要中的步骤说明
     * @return 可落库的计划步骤列表
     */
    private List<PlanStep> buildPlanSteps(Long planId, List<CompletedStep> completedSteps,
                                          List<FinalSummaryResult.StepSummary> stepSummaries) {
        if (planStepAssembler == null) {
            planStepAssembler = new AgentPlanStepAssembler(jsonUtil);
        }
        return planStepAssembler.buildPlanSteps(planId, completedSteps, stepSummaries);
    }

    /**
     * Agent 最终化结果。
     *
     * @param planId 已生成计划 ID
     */
    public record AgentPlanFinalizationResult(Long planId) {
    }
}
