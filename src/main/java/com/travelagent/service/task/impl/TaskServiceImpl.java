package com.travelagent.service.task.impl;

import com.travelagent.agent.context.CompletedStep;
import com.travelagent.agent.context.DailyTimeWindow;
import com.travelagent.agent.context.PlanningConfig;
import com.travelagent.agent.context.RetryState;
import com.travelagent.agent.context.TaskCheckpoint;
import com.travelagent.agent.planner.MarkovPlanner;
import com.travelagent.agent.planner.PlanNextAttractionRequest;
import com.travelagent.agent.planner.PlanningResult;
import com.travelagent.agent.statemachine.AgentEvent;
import com.travelagent.agent.statemachine.AgentStateMachine;
import com.travelagent.client.amap.AmapClient;
import com.travelagent.exception.BusinessException;
import com.travelagent.exception.TaskNotFoundException;
import com.travelagent.mapper.TaskMapper;
import com.travelagent.model.dto.ConfirmOriginSelectionRequest;
import com.travelagent.model.dto.CreateTaskRequest;
import com.travelagent.model.dto.LocationCandidateItem;
import com.travelagent.model.dto.NodeChatRequest;
import com.travelagent.model.dto.RewindTaskRequest;
import com.travelagent.model.dto.ResolvedLocation;
import com.travelagent.model.dto.SelectionOptionItem;
import com.travelagent.model.dto.TaskResponse;
import com.travelagent.model.entity.Task;
import com.travelagent.model.entity.UserQuotaConfig;
import com.travelagent.model.enums.TaskStatus;
import com.travelagent.service.llm.LlmUsageAccountingService;
import com.travelagent.service.notification.SseEvent;
import com.travelagent.service.notification.SseNotificationService;
import com.travelagent.service.task.OriginCandidateService;
import com.travelagent.service.task.TaskProgressService;
import com.travelagent.service.task.TaskService;
import com.travelagent.service.user.QuotaService;
import com.travelagent.util.JsonUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class TaskServiceImpl implements TaskService {

    private static final Logger log = LoggerFactory.getLogger(TaskServiceImpl.class);
    private static final int ESTIMATED_MINUTES_PER_STOP = 150;

    @Autowired private TaskMapper taskMapper;
    @Autowired private QuotaService quotaService;
    @Autowired private AgentStateMachine stateMachine;
    @Autowired private SseNotificationService sseNotificationService;
    @Autowired private JsonUtil jsonUtil;
    @Autowired private OriginCandidateService originCandidateService;
    @Autowired private AmapClient amapClient;
    @Autowired private MarkovPlanner markovPlanner;
    @Autowired private TaskProgressService taskProgressService;
    @Autowired private LlmUsageAccountingService llmUsageAccountingService;
    @Autowired private TaskRewindHandler rewindHandler;

    @Override
    @Transactional
    public TaskResponse createTask(Long userId, int userLevel, CreateTaskRequest request) {
        quotaService.checkDailyQuota(userId, userLevel);
        validateCreateRequest(request);

        UserQuotaConfig config = quotaService.getQuotaConfig(userLevel);
        int activeCount = taskMapper.countActiveByUserId(userId);
        if (activeCount >= config.getMaxConcurrentTasks()) {
            throw new BusinessException(429,
                    String.format("active task limit reached (%d)", config.getMaxConcurrentTasks()));
        }

        Task task = new Task();
        task.setTaskUuid(UUID.randomUUID().toString());
        task.setUserId(userId);
        task.setStatus(TaskStatus.PENDING.getCode());
        task.setRegion(request.getRegion());
        task.setSchemaVersion("1.0");
        task.setTotalTokensUsed(0);
        taskMapper.insert(task);

        TaskCheckpoint checkpoint = buildInitialCheckpoint(task, request);
        task.setCheckpointJson(jsonUtil.toJson(checkpoint));
        taskMapper.updateCheckpoint(task);

        log.info("Created task uuid={} for userId={}", task.getTaskUuid(), userId);
        return TaskResponse.from(task, checkpoint);
    }

    @Override
    public TaskResponse getTask(String taskUuid, Long requestingUserId) {
        Task task = loadAndVerifyOwnership(taskUuid, requestingUserId);
        return TaskResponse.from(task, parseCheckpoint(task));
    }

    @Override
    public List<TaskResponse> listTasks(Long userId) {
        return taskMapper.findByUserId(userId).stream()
                .map(task -> TaskResponse.from(task, parseCheckpoint(task)))
                .collect(Collectors.toList());
    }

    @Override
    @Transactional
    public void cancelTask(String taskUuid, Long requestingUserId) {
        Task task = loadAndVerifyOwnership(taskUuid, requestingUserId);
        TaskStatus current = TaskStatus.fromCode(task.getStatus());
        if (current.isTerminal()) {
            throw new BusinessException(400, "task is already terminal");
        }

        TaskStatus next = stateMachine.transition(current, AgentEvent.CANCEL);
        taskMapper.updateStatus(task.getId(), next.getCode());
        sseNotificationService.sendEvent(taskUuid, SseEvent.STATE_CHANGE,
                Map.of("status", next.getCode(), "taskUuid", taskUuid));
        sseNotificationService.completeEmitter(taskUuid);
    }

    @Override
    @Transactional
    public TaskResponse resumeTask(String taskUuid, Long requestingUserId) {
        Task task = loadAndVerifyOwnership(taskUuid, requestingUserId);
        TaskStatus current = TaskStatus.fromCode(task.getStatus());
        if (!current.isResumable()) {
            throw new BusinessException(400, "only paused tasks can be resumed");
        }

        TaskCheckpoint checkpoint = parseCheckpoint(task);
        if (checkpoint != null) {
            checkpoint.setPauseReason(null);
            checkpoint.setResumableAt(null);
            checkpoint.setCurrentState(TaskStatus.RESUMING.getCode());
            task.setCheckpointJson(jsonUtil.toJson(checkpoint));
            taskMapper.updateCheckpoint(task);
        }

        TaskStatus next = stateMachine.transition(current, AgentEvent.RESUME);
        taskMapper.updateStatus(task.getId(), next.getCode());
        Task updated = taskMapper.findByUuid(taskUuid);
        return TaskResponse.from(updated, parseCheckpoint(updated));
    }

    @Override
    @Transactional
    public TaskResponse confirmOriginSelection(String taskUuid, Long requestingUserId, ConfirmOriginSelectionRequest request) {
        Task task = loadAndVerifyOwnership(taskUuid, requestingUserId);
        TaskStatus current = TaskStatus.fromCode(task.getStatus());
        if (!current.isAwaitingUserInput()) {
            throw new BusinessException(400, "task is not waiting for selection");
        }

        TaskCheckpoint checkpoint = parseCheckpoint(task);
        if (checkpoint == null) {
            throw new BusinessException(400, "task checkpoint is missing");
        }
        String pendingInputType = resolvePendingInputType(checkpoint, request);
        LocationCandidateItem candidate = null;

        if ("origin_selection".equals(pendingInputType)) {
            List<LocationCandidateItem> pendingCandidates = resolvePendingCandidates(checkpoint, pendingInputType);
            if (pendingCandidates.isEmpty()) {
                throw new BusinessException(400, "no pending candidates available");
            }
            candidate = pendingCandidates.stream()
                    .filter(item -> item.getCandidateId().equals(request.getSelectedCandidateId()))
                    .findFirst()
                    .orElseThrow(() -> new BusinessException(400, "selected candidate does not belong to this task"));
            if (checkpoint.isOriginConfirmed()) {
                throw new BusinessException(400, "origin has already been selected");
            }
            checkpoint.setSelectedOrigin(toResolvedLocation(candidate, request));
            checkpoint.setOriginConfirmed(true);
            checkpoint.setLocationCandidates(List.of());
        } else if ("selection_branch".equals(pendingInputType)) {
            SelectionOptionItem option = resolvePendingSelectionOption(checkpoint, request.getSelectedCandidateId());
            checkpoint.setSelectedBranchType(option.getBranchType());
            Map<String, Object> mergedContext = checkpoint.getCurrentContext() == null
                    ? new LinkedHashMap<>()
                    : new LinkedHashMap<>(checkpoint.getCurrentContext());
            mergedContext.put("selectedBranchType", option.getBranchType());
            checkpoint.setCurrentContext(mergedContext);
        } else if ("attraction_selection".equals(pendingInputType)
                || "poi_candidate_selection".equals(pendingInputType)
                || "route_candidate_selection".equals(pendingInputType)) {
            List<LocationCandidateItem> pendingCandidates = resolvePendingCandidates(checkpoint, pendingInputType);
            if (pendingCandidates.isEmpty()) {
                throw new BusinessException(400, "no pending candidates available");
            }
            candidate = pendingCandidates.stream()
                    .filter(item -> item.getCandidateId().equals(request.getSelectedCandidateId()))
                    .findFirst()
                    .orElseThrow(() -> new BusinessException(400, "selected candidate does not belong to this task"));
            checkpoint.setSelectedAttractionCandidate(mergeSelectedCandidate(candidate, request));
        } else {
            throw new BusinessException(400, "unsupported pending input type: " + pendingInputType);
        }

        checkpoint.setPendingInputType(null);
        checkpoint.setSelectionStage(null);
        checkpoint.setSelectionOptions(List.of());
        checkpoint.setRecommendationCandidates(List.of());
        if (!"selection_branch".equals(pendingInputType)) {
            checkpoint.setCurrentContext(new LinkedHashMap<>());
        }
        if ("origin_selection".equals(pendingInputType)) {
            checkpoint.setWeatherContext(new LinkedHashMap<>());
        }
        checkpoint.setCurrentState(TaskStatus.RESUMING.getCode());
        checkpoint.setPauseReason(null);
        checkpoint.setResumableAt(null);
        task.setStatus(TaskStatus.RESUMING.getCode());
        task.setCheckpointJson(jsonUtil.toJson(checkpoint));

        TaskStatus next = stateMachine.transition(current, AgentEvent.USER_INPUT_RECEIVED);
        taskMapper.updateStatus(task.getId(), next.getCode());
        taskMapper.updateCheckpoint(task);

        Map<String, Object> payload = buildSelectionConfirmedPayload(taskUuid, pendingInputType, checkpoint, candidate, request);
        sseNotificationService.sendEvent(taskUuid, SseEvent.USER_SELECTION_CONFIRMED, payload);
        sseNotificationService.sendEvent(taskUuid, SseEvent.STATE_CHANGE, Map.of(
                "status", next.getCode(),
                "taskUuid", taskUuid,
                "pendingInputType", pendingInputType != null ? pendingInputType : ""
        ));

        Task updated = taskMapper.findByUuid(taskUuid);
        return TaskResponse.from(updated, checkpoint);
    }

    @Override
    @Transactional
    public TaskResponse rewindTask(String taskUuid, Long requestingUserId, RewindTaskRequest request) {
        Task task = loadAndVerifyOwnership(taskUuid, requestingUserId);
        TaskStatus current = TaskStatus.fromCode(task.getStatus());
        if (current != TaskStatus.PAUSED && current != TaskStatus.AWAITING_USER_INPUT) {
            throw new BusinessException(400, "only paused or awaiting_user_input tasks can be rewound");
        }

        TaskCheckpoint checkpoint = parseCheckpoint(task);
        if (checkpoint == null || checkpoint.getCompletedSteps() == null || checkpoint.getCompletedSteps().isEmpty()) {
            throw new BusinessException(400, "no completed steps available for rewind");
        }

        int targetStepIndex = request.getTargetStepIndex();
        if (targetStepIndex < 0 || targetStepIndex >= checkpoint.getCompletedSteps().size()) {
            throw new BusinessException(400, "target step index is out of range");
        }

        List<CompletedStep> retainedSteps = rewindHandler.applyRewind(checkpoint, targetStepIndex);

        task.setStatus(TaskStatus.RESUMING.getCode());
        task.setCheckpointJson(jsonUtil.toJson(checkpoint));
        taskMapper.updateCheckpoint(task);
        taskMapper.updateStatus(task.getId(), TaskStatus.RESUMING.getCode());

        String targetName = retainedSteps.get(retainedSteps.size() - 1).getAttractionName();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("taskUuid", taskUuid);
        payload.put("targetStepIndex", targetStepIndex);
        payload.put("targetStepName", targetName);
        payload.put("remainingTimeBudgetMin", checkpoint.getRemainingTimeBudgetMin());
        payload.put("status", TaskStatus.RESUMING.getCode());

        taskProgressService.recordEvent(taskUuid, "REWIND", TaskStatus.RESUMING.getCode(),
                targetStepIndex, checkpoint.totalPlannedSteps(),
                "Rewound task to step " + targetStepIndex, payload);
        sseNotificationService.sendEvent(taskUuid, SseEvent.REWIND, payload);
        sseNotificationService.sendEvent(taskUuid, SseEvent.STATE_CHANGE, Map.of(
                "status", TaskStatus.RESUMING.getCode(),
                "taskUuid", taskUuid,
                "totalTokensUsed", task.getTotalTokensUsed() == null ? 0 : task.getTotalTokensUsed(),
                "remainingTimeBudgetMin", checkpoint.getRemainingTimeBudgetMin()
        ));

        return TaskResponse.from(task, checkpoint);
    }

    @Override
    @Transactional
    public TaskResponse refreshNodeSelection(String taskUuid, Long requestingUserId, NodeChatRequest request) {
        Task task = loadAndVerifyOwnership(taskUuid, requestingUserId);
        TaskStatus current = TaskStatus.fromCode(task.getStatus());
        if (current != TaskStatus.AWAITING_USER_INPUT) {
            throw new BusinessException(400, "task is not waiting for node input");
        }

        TaskCheckpoint checkpoint = parseCheckpoint(task);
        if (checkpoint == null) {
            throw new BusinessException(400, "task checkpoint is missing");
        }

        String pendingInputType = resolvePendingInputType(checkpoint, request.getPendingInputType());
        if ("origin_selection".equals(pendingInputType)) {
            throw new BusinessException(400, "origin selection does not support node preference refresh");
        }

        Map<String, Object> currentContext = checkpoint.getCurrentContext() == null
                ? new LinkedHashMap<>()
                : new LinkedHashMap<>(checkpoint.getCurrentContext());
        currentContext.put("userPreferencePrompt", request.getMessage().trim());
        checkpoint.setCurrentContext(currentContext);
        checkpoint.setSelectedAttractionCandidate(null);
        checkpoint.setPendingInputType(null);
        checkpoint.setSelectionStage(null);
        checkpoint.setSelectionOptions(new ArrayList<>());
        checkpoint.setRecommendationCandidates(new ArrayList<>());
        checkpoint.setWeatherContext(new LinkedHashMap<>());

        if ("selection_branch".equals(pendingInputType)) {
            checkpoint.setSelectedBranchType(null);
        } else if ("route_candidate_selection".equals(pendingInputType)) {
            checkpoint.setSelectedBranchType("route_plan");
        } else if ("poi_candidate_selection".equals(pendingInputType) || "attraction_selection".equals(pendingInputType)) {
            if (checkpoint.getSelectedBranchType() == null || checkpoint.getSelectedBranchType().isBlank()) {
                checkpoint.setSelectedBranchType("nearby_poi");
            }
        } else {
            throw new BusinessException(400, "unsupported pending input type for node preference: " + pendingInputType);
        }

        PlanNextAttractionRequest planningRequest = markovPlanner.buildPlanRequest(checkpoint);
        PlanningResult planResult = markovPlanner.planNextAttraction(task, checkpoint, planningRequest, taskUuid);
        if (planResult.totalTokens() > 0) {
            int updatedTotal = llmUsageAccountingService.recordUsage(task.getId(), task.getUserId(), planResult.totalTokens());
            task.setTotalTokensUsed(updatedTotal);
        }
        if (!planResult.requiresUserSelection()) {
            throw new BusinessException(400, "node preference refresh did not produce selectable candidates");
        }

        checkpoint.setPendingInputType(planResult.pendingInputType());
        checkpoint.setSelectionStage(planResult.selectionStage());
        checkpoint.setSelectedBranchType(planResult.selectedBranchType());
        checkpoint.setSelectionOptions(planResult.selectionOptions() == null
                ? new ArrayList<>()
                : new ArrayList<>(planResult.selectionOptions()));
        checkpoint.setRecommendationCandidates(planResult.recommendationCandidates() == null
                ? new ArrayList<>()
                : new ArrayList<>(planResult.recommendationCandidates()));
        Map<String, Object> refreshedContext = planResult.currentContext() == null
                ? new LinkedHashMap<>()
                : new LinkedHashMap<>(planResult.currentContext());
        refreshedContext.put("userPreferencePrompt", request.getMessage().trim());
        checkpoint.setCurrentContext(refreshedContext);
        checkpoint.setWeatherContext(planResult.weatherContext() == null
                ? new LinkedHashMap<>()
                : new LinkedHashMap<>(planResult.weatherContext()));
        checkpoint.setCurrentState(TaskStatus.AWAITING_USER_INPUT.getCode());

        task.setStatus(TaskStatus.AWAITING_USER_INPUT.getCode());
        task.setCheckpointJson(jsonUtil.toJson(checkpoint));
        taskMapper.updateCheckpoint(task);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("taskUuid", taskUuid);
        payload.put("pendingInputType", checkpoint.getPendingInputType());
        payload.put("selectionStage", checkpoint.getSelectionStage());
        payload.put("selectedBranchType", checkpoint.getSelectedBranchType());
        payload.put("selectionOptions", checkpoint.getSelectionOptions());
        payload.put("recommendationCandidates", checkpoint.getRecommendationCandidates());
        payload.put("currentContext", checkpoint.getCurrentContext());
        payload.put("weatherContext", checkpoint.getWeatherContext());
        payload.put("totalTokensUsed", task.getTotalTokensUsed() == null ? 0 : task.getTotalTokensUsed());

        taskProgressService.recordEvent(taskUuid, "NODE_CHAT", TaskStatus.AWAITING_USER_INPUT.getCode(),
                checkpoint.getCurrentStepIndex(), checkpoint.totalPlannedSteps(),
                "Refreshed current node candidates from preference prompt", Map.of(
                        "pendingInputType", pendingInputType,
                        "message", request.getMessage().trim()
                ));
        taskProgressService.recordEvent(taskUuid, "USER_SELECTION_REQUIRED", TaskStatus.AWAITING_USER_INPUT.getCode(),
                checkpoint.getCurrentStepIndex(), checkpoint.totalPlannedSteps(),
                "Waiting for refreshed user selection", payload);
        sseNotificationService.sendEvent(taskUuid, SseEvent.USER_SELECTION_REQUIRED, payload);

        return TaskResponse.from(task, checkpoint);
    }

    @Override
    public Task getTaskEntity(String taskUuid, Long requestingUserId) {
        return loadAndVerifyOwnership(taskUuid, requestingUserId);
    }

    private void validateCreateRequest(CreateTaskRequest request) {
        if (request.getStartTime() == null || request.getEndTime() == null
                || !request.getEndTime().isAfter(request.getStartTime())) {
            throw new BusinessException(400, "end time must be later than start time");
        }
        if ((request.getFullDayStartTime() == null) != (request.getFullDayEndTime() == null)) {
            throw new BusinessException(400, "full day start time and end time must be provided together");
        }
        if (request.getFullDayStartTime() != null
                && !request.getFullDayEndTime().isAfter(request.getFullDayStartTime())) {
            throw new BusinessException(400, "full day end time must be later than full day start time");
        }
    }

    private TaskCheckpoint buildInitialCheckpoint(Task task, CreateTaskRequest req) {
        int totalDays = calculateTotalDays(req.getStartTime(), req.getEndTime());
        PlanningConfig config = buildPlanningConfig(req, totalDays);
        List<DailyTimeWindow> dailyWindows = buildDailyWindows(config);
        int totalAvailableMinutes = totalAvailableMinutes(dailyWindows);
        int dynamicTargetSteps = computeDynamicTargetSteps(totalAvailableMinutes, config);
        config.setDynamicTargetSteps(dynamicTargetSteps);

        TaskCheckpoint cp = new TaskCheckpoint();
        cp.setSchemaVersion("1.0");
        cp.setTaskId(task.getId());
        cp.setUserId(task.getUserId());
        cp.setTaskUuid(task.getTaskUuid());
        cp.setCurrentState(TaskStatus.PENDING.getCode());
        cp.setRegion(req.getRegion());
        cp.setUserIntent(req.getUserIntent());
        cp.setStartLocationQuery(req.getStartLocationQuery());
        cp.setEndLocationQuery(req.getEndLocationQuery());
        cp.setTripStartTime(req.getStartTime());
        cp.setTripEndTime(req.getEndTime());
        cp.setPlanningConfig(config);
        cp.setCurrentStepIndex(0);
        cp.setRetryState(new RetryState(0, 3));
        cp.setDailyTimeWindows(dailyWindows);
        cp.setUsedTimeBudgetMin(0);
        cp.setProjectedReturnToDestinationMin(0);
        cp.setRemainingTimeBudgetMin(Math.max(0, totalAvailableMinutes - config.getDestinationBufferMin()));
        cp.setLocationCandidates(originCandidateService.generateCandidates(req.getRegion(), req.getStartLocationQuery()));
        cp.setRecommendationCandidates(new ArrayList<>(cp.getLocationCandidates()));
        cp.setSelectionOptions(new ArrayList<>());
        cp.setSelectionStage("origin_selection");
        cp.setSelectedBranchType(null);
        cp.setCurrentContext(new LinkedHashMap<>());
        cp.setWeatherContext(new LinkedHashMap<>());
        cp.setSelectedDestination(resolveDestination(req.getRegion(), req.getEndLocationQuery()));
        cp.setOriginConfirmed(false);
        cp.setPauseReason(null);
        return cp;
    }

    private PlanningConfig buildPlanningConfig(CreateTaskRequest req, int totalDays) {
        PlanningConfig config = new PlanningConfig();
        config.setTotalDays(totalDays);
        config.setPreferenceKeywords(req.getPreferenceKeywords());
        config.setTravelMode(req.getTravelMode());
        config.setStartLocationQuery(req.getStartLocationQuery());
        config.setEndLocationQuery(req.getEndLocationQuery());
        config.setStartTime(req.getStartTime());
        config.setEndTime(req.getEndTime());
        config.setFullDayStartTime(req.getFullDayStartTime());
        config.setFullDayEndTime(req.getFullDayEndTime());
        return config;
    }

    private int calculateTotalDays(LocalDateTime startTime, LocalDateTime endTime) {
        LocalDate startDate = startTime.toLocalDate();
        LocalDate endDate = endTime.toLocalDate();
        return (int) (Duration.between(startDate.atStartOfDay(), endDate.atStartOfDay()).toDays() + 1);
    }

    private List<DailyTimeWindow> buildDailyWindows(PlanningConfig config) {
        List<DailyTimeWindow> windows = new ArrayList<>();
        LocalDateTime tripStart = config.getStartTime();
        LocalDateTime tripEnd = config.getEndTime();
        LocalTime fullDayStart = config.resolveFullDayStartTime();
        LocalTime fullDayEnd = config.resolveFullDayEndTime();

        for (int i = 0; i < config.getTotalDays(); i++) {
            LocalDate currentDate = tripStart.toLocalDate().plusDays(i);
            LocalDateTime dayStart;
            LocalDateTime dayEnd;
            if (config.getTotalDays() == 1) {
                dayStart = tripStart;
                dayEnd = tripEnd;
            } else if (i == 0) {
                dayStart = tripStart;
                dayEnd = LocalDateTime.of(currentDate, fullDayEnd);
            } else if (i == config.getTotalDays() - 1) {
                dayStart = LocalDateTime.of(currentDate, fullDayStart);
                dayEnd = tripEnd;
            } else {
                dayStart = LocalDateTime.of(currentDate, fullDayStart);
                dayEnd = LocalDateTime.of(currentDate, fullDayEnd);
            }
            windows.add(new DailyTimeWindow(i + 1, dayStart, dayEnd));
        }
        return windows;
    }

    private int totalAvailableMinutes(List<DailyTimeWindow> windows) {
        return windows.stream().mapToInt(DailyTimeWindow::availableMinutes).sum();
    }

    private int computeDynamicTargetSteps(int totalAvailableMinutes, PlanningConfig config) {
        int effectiveMinutes = Math.max(0, totalAvailableMinutes - config.getDestinationBufferMin());
        return Math.max(1, effectiveMinutes / ESTIMATED_MINUTES_PER_STOP);
    }

    private ResolvedLocation resolveDestination(String region, String endLocationQuery) {
        ResolvedLocation destination = new ResolvedLocation();
        destination.setName(endLocationQuery);
        destination.setRegion(region);
        destination.setSource("query");
        try {
            Map<String, Object> geocode = amapClient.geocode(endLocationQuery, region);
            destination.setLatitude(((Number) geocode.get("lat")).doubleValue());
            destination.setLongitude(((Number) geocode.get("lng")).doubleValue());
            destination.setAdcode(String.valueOf(geocode.getOrDefault("adcode", "")));
            destination.setSource("geocode");
            destination.setCandidateId("geo:" + endLocationQuery.trim().toLowerCase());
        } catch (Exception e) {
            log.warn("Failed to geocode destination '{}': {}", endLocationQuery, e.getMessage());
        }
        return destination;
    }

    private Task loadAndVerifyOwnership(String taskUuid, Long requestingUserId) {
        Task task = taskMapper.findByUuid(taskUuid);
        if (task == null) {
            throw new TaskNotFoundException(taskUuid);
        }
        if (!task.getUserId().equals(requestingUserId)) {
            throw new BusinessException(403, "forbidden");
        }
        return task;
    }

    private TaskCheckpoint parseCheckpoint(Task task) {
        if (task.getCheckpointJson() == null || task.getCheckpointJson().isBlank()) {
            return null;
        }
        try {
            return jsonUtil.fromJson(task.getCheckpointJson(), TaskCheckpoint.class);
        } catch (Exception e) {
            log.warn("Failed to parse checkpoint for task={}: {}", task.getTaskUuid(), e.getMessage());
            return null;
        }
    }

    private String resolvePendingInputType(TaskCheckpoint checkpoint, ConfirmOriginSelectionRequest request) {
        return resolvePendingInputType(checkpoint, request.getPendingInputType());
    }

    private String resolvePendingInputType(TaskCheckpoint checkpoint, String requestType) {
        String checkpointType = checkpoint.getPendingInputType();
        if (requestType == null || requestType.isBlank()) {
            return checkpointType;
        }
        if (checkpointType != null && !checkpointType.isBlank() && !checkpointType.equals(requestType)) {
            throw new BusinessException(400, "pending input type mismatch");
        }
        return requestType;
    }

    private List<LocationCandidateItem> resolvePendingCandidates(TaskCheckpoint checkpoint, String pendingInputType) {
        if ("origin_selection".equals(pendingInputType)) {
            return checkpoint.getLocationCandidates() == null ? List.of() : checkpoint.getLocationCandidates();
        }
        if ("attraction_selection".equals(pendingInputType)
                || "poi_candidate_selection".equals(pendingInputType)
                || "route_candidate_selection".equals(pendingInputType)) {
            return checkpoint.getRecommendationCandidates() == null ? List.of() : checkpoint.getRecommendationCandidates();
        }
        return List.of();
    }

    private SelectionOptionItem resolvePendingSelectionOption(TaskCheckpoint checkpoint, String selectedOptionId) {
        List<SelectionOptionItem> options = checkpoint.getSelectionOptions() == null
                ? List.of()
                : checkpoint.getSelectionOptions();
        if (options.isEmpty()) {
            throw new BusinessException(400, "no pending selection options available");
        }
        return options.stream()
                .filter(option -> option.getOptionId().equals(selectedOptionId))
                .findFirst()
                .orElseThrow(() -> new BusinessException(400, "selected option does not belong to this task"));
    }

    private ResolvedLocation toResolvedLocation(LocationCandidateItem candidate, ConfirmOriginSelectionRequest request) {
        ResolvedLocation resolvedLocation = new ResolvedLocation();
        resolvedLocation.setCandidateId(candidate.getCandidateId());
        resolvedLocation.setName(request.getSelectedCandidateName());
        resolvedLocation.setRegion(candidate.getRegion());
        resolvedLocation.setDistrict(candidate.getDistrict());
        resolvedLocation.setAddress(candidate.getAddress());
        Double lat = request.getSelectedLat() != null ? request.getSelectedLat() : candidate.getLatitude();
        Double lng = request.getSelectedLng() != null ? request.getSelectedLng() : candidate.getLongitude();
        if (lat == null || lng == null) {
            throw new BusinessException(400, "所选地点缺少坐标信息，请重新搜索或选择其他候选");
        }
        resolvedLocation.setLatitude(lat);
        resolvedLocation.setLongitude(lng);
        resolvedLocation.setAdcode(candidate.getAdcode());
        resolvedLocation.setSource(candidate.getSource());
        return resolvedLocation;
    }

    private LocationCandidateItem mergeSelectedCandidate(LocationCandidateItem candidate, ConfirmOriginSelectionRequest request) {
        LocationCandidateItem selected = new LocationCandidateItem();
        selected.setCandidateId(candidate.getCandidateId());
        selected.setCandidateType(candidate.getCandidateType());
        selected.setBranchType(candidate.getBranchType());
        selected.setName(request.getSelectedCandidateName());
        selected.setTargetAttractionName(candidate.getTargetAttractionName());
        selected.setRegion(candidate.getRegion());
        selected.setDistrict(candidate.getDistrict());
        selected.setCategory(candidate.getCategory());
        selected.setAddress(candidate.getAddress());
        selected.setLatitude(request.getSelectedLat() != null ? request.getSelectedLat() : candidate.getLatitude());
        selected.setLongitude(request.getSelectedLng() != null ? request.getSelectedLng() : candidate.getLongitude());
        selected.setAdcode(candidate.getAdcode());
        selected.setSource(candidate.getSource());
        selected.setScore(candidate.getScore());
        selected.setRouteSummary(candidate.getRouteSummary());
        selected.setVisitDurationMin(candidate.getVisitDurationMin());
        selected.setEstimatedTotalDurationMin(candidate.getEstimatedTotalDurationMin());
        selected.setWeatherSuitability(candidate.getWeatherSuitability());
        selected.setExplanations(candidate.getExplanations() == null ? List.of() : candidate.getExplanations());
        selected.setHighlights(candidate.getHighlights() == null ? List.of() : candidate.getHighlights());
        selected.setRouteStops(candidate.getRouteStops() == null ? List.of() : candidate.getRouteStops());
        return selected;
    }

    private Map<String, Object> buildSelectionConfirmedPayload(String taskUuid,
                                                               String pendingInputType,
                                                               TaskCheckpoint checkpoint,
                                                               LocationCandidateItem candidate,
                                                               ConfirmOriginSelectionRequest request) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("taskUuid", taskUuid);
        payload.put("pendingInputType", pendingInputType);
        if (candidate != null && !"origin_selection".equals(pendingInputType)) {
            payload.put("selectedCandidate", mergeSelectedCandidate(candidate, request));
        }
        if (checkpoint.getSelectedBranchType() != null) {
            payload.put("selectedBranchType", checkpoint.getSelectedBranchType());
        }
        if (checkpoint.getSelectedOrigin() != null) {
            payload.put("selectedOrigin", checkpoint.getSelectedOrigin());
        }
        return payload;
    }

}
