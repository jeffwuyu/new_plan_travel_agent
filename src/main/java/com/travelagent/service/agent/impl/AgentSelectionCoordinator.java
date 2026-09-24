package com.travelagent.service.agent.impl;

import com.travelagent.agent.context.TaskCheckpoint;
import com.travelagent.agent.planner.PlanningResult;
import com.travelagent.agent.statemachine.AgentEvent;
import com.travelagent.agent.statemachine.AgentStateMachine;
import com.travelagent.mapper.TaskMapper;
import com.travelagent.model.dto.LocationCandidateItem;
import com.travelagent.model.dto.SelectionPromptDto;
import com.travelagent.model.entity.Task;
import com.travelagent.model.enums.TaskStatus;
import com.travelagent.service.agent.SelectionPolicyService;
import com.travelagent.service.notification.SseEvent;
import com.travelagent.service.notification.SseNotificationService;
import com.travelagent.service.task.TaskProgressService;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Agent 用户选择协作器，集中处理起点确认、候选景点等待、自动选择和选择上下文组装。
 */
@Component
public class AgentSelectionCoordinator {

    private static final String EVT_USER_SELECTION_REQUIRED = "USER_SELECTION_REQUIRED";

    private final TaskMapper taskMapper;
    private final AgentStateMachine stateMachine;
    private final SseNotificationService sseNotificationService;
    private final TaskProgressService taskProgressService;
    private final AgentCheckpointHelper checkpointHelper;
    private final SelectionPolicyService selectionPolicyService;

    /**
     * 创建 Agent 用户选择协作器。
     *
     * @param taskMapper 任务数据访问器
     * @param stateMachine Agent 状态机
     * @param sseNotificationService SSE 推送服务
     * @param taskProgressService 任务进度事件服务
     * @param checkpointHelper checkpoint 读写助手
     * @param selectionPolicyService 自动选择策略服务
     */
    public AgentSelectionCoordinator(TaskMapper taskMapper,
                                     AgentStateMachine stateMachine,
                                     SseNotificationService sseNotificationService,
                                     TaskProgressService taskProgressService,
                                     AgentCheckpointHelper checkpointHelper,
                                     SelectionPolicyService selectionPolicyService) {
        this.taskMapper = taskMapper;
        this.stateMachine = stateMachine;
        this.sseNotificationService = sseNotificationService;
        this.taskProgressService = taskProgressService;
        this.checkpointHelper = checkpointHelper;
        this.selectionPolicyService = selectionPolicyService;
    }

    /**
     * 判断 checkpoint 中是否存在可展示给用户的起点候选。
     *
     * @param checkpoint 任务 checkpoint
     * @return 存在候选时返回 true
     */
    public boolean hasOriginCandidates(TaskCheckpoint checkpoint) {
        return checkpoint.getLocationCandidates() != null && !checkpoint.getLocationCandidates().isEmpty();
    }

    /**
     * 将任务切换为等待起点选择，并向前端推送候选起点。
     *
     * @param task 任务实体
     * @param checkpoint 任务 checkpoint
     * @param taskUuid 任务唯一标识
     */
    public void awaitOriginSelection(Task task, TaskCheckpoint checkpoint, String taskUuid) {
        List<LocationCandidateItem> candidates = checkpoint.getLocationCandidates() == null
                ? List.of()
                : checkpoint.getLocationCandidates();

        TaskStatus awaiting = stateMachine.transition(TaskStatus.PLANNING, AgentEvent.USER_INPUT_REQUIRED);
        checkpoint.setPendingInputType("origin_selection");
        checkpoint.setSelectionStage("origin_selection");
        checkpoint.setSelectedBranchType(null);
        checkpoint.setSelectionOptions(List.of());
        checkpoint.setRecommendationCandidates(new ArrayList<>(candidates));
        checkpoint.setCurrentContext(Map.of());
        checkpoint.setWeatherContext(Map.of());
        checkpoint.setCurrentState(awaiting.getCode());
        task.setStatus(awaiting.getCode());
        checkpointHelper.saveCheckpoint(task, checkpoint);
        taskMapper.updateStatus(task.getId(), awaiting.getCode());

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("taskUuid", taskUuid);
        payload.put("startLocationQuery", checkpoint.getStartLocationQuery());
        payload.put("pendingInputType", "origin_selection");
        payload.put("locationCandidates", candidates);
        payload.put("recommendationCandidates", candidates);
        payload.put("currentContext", Map.of());
        payload.put("selectionPrompt", SelectionPromptDto.from(
                "origin_selection",
                "origin_selection",
                null,
                checkpoint.getStartLocationQuery(),
                checkpoint.getCurrentStepIndex(),
                null,
                Map.of(),
                Map.of()));

        sseNotificationService.sendEvent(taskUuid, SseEvent.USER_SELECTION_REQUIRED, payload);
        taskProgressService.recordEvent(taskUuid, EVT_USER_SELECTION_REQUIRED, awaiting.getCode(),
                checkpoint.getCurrentStepIndex(), checkpoint.totalPlannedSteps(),
                "Waiting for origin selection", payload);
    }

    /**
     * 判断规划结果是否包含足够的用户选择数据。
     *
     * @param planResult 本轮规划结果
     * @return 有选项、候选或空候选提示时返回 true
     */
    public boolean hasSelectionData(PlanningResult planResult) {
        boolean needsOptions = planResult.selectionOptions() != null && !planResult.selectionOptions().isEmpty();
        boolean needsCandidates = planResult.recommendationCandidates() != null
                && !planResult.recommendationCandidates().isEmpty();
        boolean hasEmptyCandidateMessage = planResult.currentContext() != null
                && planResult.currentContext().get("emptyCandidateMessage") != null;
        return needsOptions || needsCandidates || hasEmptyCandidateMessage;
    }

    /**
     * 按配置策略尝试从规划结果中自动选择高置信候选。
     *
     * @param planResult 本轮规划结果
     * @return 可自动确认的候选
     */
    public Optional<LocationCandidateItem> chooseAutoCandidate(PlanningResult planResult) {
        return selectionPolicyService.chooseCandidate(planResult);
    }

    /**
     * 将任务切换为等待景点或分支选择，并持久化待选上下文。
     *
     * @param task 任务实体
     * @param checkpoint 任务 checkpoint
     * @param taskUuid 任务唯一标识
     * @param stepIndex 当前步骤序号
     * @param dayNumber 当前行程天数
     * @param planResult 本轮规划生成的候选与上下文
     */
    public void awaitAttractionSelection(Task task,
                                         TaskCheckpoint checkpoint,
                                         String taskUuid,
                                         int stepIndex,
                                         int dayNumber,
                                         PlanningResult planResult) {
        TaskStatus awaiting = stateMachine.transition(TaskStatus.PLANNING, AgentEvent.USER_INPUT_REQUIRED);
        Map<String, Object> currentContext = new LinkedHashMap<>();
        if (planResult.currentContext() != null && !planResult.currentContext().isEmpty()) {
            currentContext.putAll(planResult.currentContext());
        } else {
            currentContext.putAll(buildAttractionSelectionContext(checkpoint, stepIndex, dayNumber));
        }
        checkpoint.setPendingInputType(planResult.pendingInputType());
        checkpoint.setSelectionStage(planResult.selectionStage());
        checkpoint.setSelectedBranchType(planResult.selectedBranchType());
        checkpoint.setSelectionOptions(planResult.selectionOptions() == null
                ? List.of()
                : new ArrayList<>(planResult.selectionOptions()));
        checkpoint.setRecommendationCandidates(planResult.recommendationCandidates() == null
                ? List.of()
                : new ArrayList<>(planResult.recommendationCandidates()));
        checkpoint.setCurrentContext(currentContext);
        checkpoint.setWeatherContext(planResult.weatherContext() == null ? Map.of() : new LinkedHashMap<>(planResult.weatherContext()));
        checkpoint.setCurrentState(awaiting.getCode());
        task.setStatus(awaiting.getCode());
        checkpointHelper.saveCheckpoint(task, checkpoint);
        taskMapper.updateStatus(task.getId(), awaiting.getCode());

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("taskUuid", taskUuid);
        payload.put("pendingInputType", planResult.pendingInputType());
        payload.put("selectionStage", planResult.selectionStage());
        payload.put("selectedBranchType", planResult.selectedBranchType());
        payload.put("stepIndex", stepIndex);
        payload.put("dayNumber", dayNumber);
        payload.put("selectionOptions", checkpoint.getSelectionOptions());
        payload.put("recommendationCandidates", checkpoint.getRecommendationCandidates());
        payload.put("currentContext", currentContext);
        payload.put("weatherContext", checkpoint.getWeatherContext());
        payload.put("selectionPrompt", SelectionPromptDto.from(
                planResult.pendingInputType(),
                planResult.selectionStage(),
                planResult.selectedBranchType(),
                checkpoint.getStartLocationQuery(),
                stepIndex,
                dayNumber,
                currentContext,
                checkpoint.getWeatherContext()));

        sseNotificationService.sendEvent(taskUuid, SseEvent.USER_SELECTION_REQUIRED, payload);
        taskProgressService.recordEvent(taskUuid, EVT_USER_SELECTION_REQUIRED, awaiting.getCode(),
                stepIndex, checkpoint.totalPlannedSteps(),
                "Waiting for user selection", payload);
    }

    /**
     * 应用自动选择结果，并记录可被前端展示的自动确认事件。
     *
     * @param task 任务实体
     * @param checkpoint 任务 checkpoint
     * @param taskUuid 任务唯一标识
     * @param stepIndex 当前步骤序号
     * @param dayNumber 当前行程天数
     * @param candidate 自动选中的候选
     * @param planResult 本轮规划结果
     */
    public void applyAutoSelection(Task task,
                                   TaskCheckpoint checkpoint,
                                   String taskUuid,
                                   int stepIndex,
                                   int dayNumber,
                                   LocationCandidateItem candidate,
                                   PlanningResult planResult) {
        checkpoint.setPendingInputType(null);
        checkpoint.setSelectionStage(planResult.selectionStage());
        checkpoint.setSelectedBranchType(planResult.selectedBranchType());
        checkpoint.setSelectedAttractionCandidate(candidate);
        checkpoint.setRecommendationCandidates(planResult.recommendationCandidates() == null
                ? List.of()
                : new ArrayList<>(planResult.recommendationCandidates()));
        checkpoint.setSelectionOptions(List.of());
        checkpoint.setCurrentContext(planResult.currentContext() == null ? Map.of() : new LinkedHashMap<>(planResult.currentContext()));
        checkpoint.setWeatherContext(planResult.weatherContext() == null ? Map.of() : new LinkedHashMap<>(planResult.weatherContext()));
        checkpointHelper.saveCheckpoint(task, checkpoint);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("taskUuid", taskUuid);
        payload.put("policy", selectionPolicyService.policy());
        payload.put("confidenceThreshold", selectionPolicyService.confidenceThreshold());
        payload.put("scoreGapThreshold", selectionPolicyService.scoreGapThreshold());
        payload.put("candidateId", candidate.getCandidateId());
        payload.put("candidateName", candidate.getName());
        payload.put("score", candidate.getScore());
        payload.put("stepIndex", stepIndex);
        payload.put("dayNumber", dayNumber);
        payload.put("reversible", true);

        taskProgressService.recordEvent(taskUuid, "AUTO_SELECTION_APPLIED", TaskStatus.PLANNING.getCode(),
                stepIndex, checkpoint.totalPlannedSteps(),
                "Auto selected candidate: " + candidateAttractionName(candidate), payload);
        sseNotificationService.sendEvent(taskUuid, SseEvent.AUTO_SELECTION_APPLIED, payload);
    }

    /**
     * 解析候选真正用于后续工具调用的景点名称。
     *
     * @param candidate 景点候选
     * @return 优先返回目标景点名，否则返回候选展示名
     */
    public String candidateAttractionName(LocationCandidateItem candidate) {
        if (candidate == null) {
            return "";
        }
        return candidate.getTargetAttractionName() != null && !candidate.getTargetAttractionName().isBlank()
                ? candidate.getTargetAttractionName()
                : candidate.getName();
    }

    /**
     * 构建前端选择景点时需要展示的上下文。
     *
     * @param checkpoint 任务 checkpoint
     * @param stepIndex 当前步骤序号
     * @param dayNumber 当前行程天数
     * @return 景点选择上下文
     */
    private Map<String, Object> buildAttractionSelectionContext(TaskCheckpoint checkpoint, int stepIndex, int dayNumber) {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("stepIndex", stepIndex);
        context.put("dayNumber", dayNumber);
        context.put("remainingTimeBudgetMin", checkpoint.getRemainingTimeBudgetMin());
        context.put("projectedReturnToDestinationMin", checkpoint.getProjectedReturnToDestinationMin());
        context.put("currentPositionName", resolveCurrentPositionName(checkpoint));
        context.put("destinationName", checkpoint.getSelectedDestination() != null
                ? checkpoint.getSelectedDestination().getName()
                : checkpoint.getEndLocationQuery());
        context.put("travelMode", checkpoint.getPlanningConfig() != null
                ? checkpoint.getPlanningConfig().getTravelMode()
                : null);
        return context;
    }

    /**
     * 根据已完成步骤或起点信息解析当前规划位置名称。
     *
     * @param checkpoint 任务 checkpoint
     * @return 当前用于规划上下文的位置名称
     */
    private String resolveCurrentPositionName(TaskCheckpoint checkpoint) {
        if (checkpoint.getCompletedSteps() != null && !checkpoint.getCompletedSteps().isEmpty()) {
            return checkpoint.getCompletedSteps().get(checkpoint.getCompletedSteps().size() - 1).getAttractionName();
        }
        if (checkpoint.getSelectedOrigin() != null) {
            return checkpoint.getSelectedOrigin().getName();
        }
        return checkpoint.getStartLocationQuery();
    }
}
