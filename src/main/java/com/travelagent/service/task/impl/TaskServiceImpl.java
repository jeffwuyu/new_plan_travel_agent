package com.travelagent.service.task.impl;

import com.travelagent.agent.context.DailyTimeWindow;
import com.travelagent.agent.context.PlanningConfig;
import com.travelagent.agent.context.RetryState;
import com.travelagent.agent.context.TaskCheckpoint;
import com.travelagent.agent.statemachine.AgentEvent;
import com.travelagent.agent.statemachine.AgentStateMachine;
import com.travelagent.client.amap.AmapClient;
import com.travelagent.exception.BusinessException;
import com.travelagent.exception.TaskNotFoundException;
import com.travelagent.mapper.TaskMapper;
import com.travelagent.model.dto.ConfirmOriginSelectionRequest;
import com.travelagent.model.dto.CreateTaskRequest;
import com.travelagent.model.dto.LocationCandidateItem;
import com.travelagent.model.dto.ResolvedLocation;
import com.travelagent.model.dto.TaskResponse;
import com.travelagent.model.entity.Task;
import com.travelagent.model.entity.UserQuotaConfig;
import com.travelagent.model.enums.TaskStatus;
import com.travelagent.service.notification.SseEvent;
import com.travelagent.service.notification.SseNotificationService;
import com.travelagent.service.task.OriginCandidateService;
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
        List<LocationCandidateItem> pendingCandidates = resolvePendingCandidates(checkpoint, pendingInputType);
        if (pendingCandidates.isEmpty()) {
            throw new BusinessException(400, "no pending candidates available");
        }

        LocationCandidateItem candidate = pendingCandidates.stream()
                .filter(item -> item.getCandidateId().equals(request.getSelectedCandidateId()))
                .findFirst()
                .orElseThrow(() -> new BusinessException(400, "selected candidate does not belong to this task"));

        if ("origin_selection".equals(pendingInputType)) {
            if (checkpoint.isOriginConfirmed()) {
                throw new BusinessException(400, "origin has already been selected");
            }
            checkpoint.setSelectedOrigin(toResolvedLocation(candidate, request));
            checkpoint.setOriginConfirmed(true);
            checkpoint.setLocationCandidates(List.of());
        } else if ("attraction_selection".equals(pendingInputType)) {
            checkpoint.setSelectedAttractionCandidate(mergeSelectedCandidate(candidate, request));
        } else {
            throw new BusinessException(400, "unsupported pending input type: " + pendingInputType);
        }

        checkpoint.setPendingInputType(null);
        checkpoint.setRecommendationCandidates(List.of());
        checkpoint.setCurrentContext(new LinkedHashMap<>());
        checkpoint.setCurrentState(TaskStatus.RESUMING.getCode());
        checkpoint.setPauseReason(null);
        checkpoint.setResumableAt(null);
        task.setStatus(TaskStatus.RESUMING.getCode());
        task.setCheckpointJson(jsonUtil.toJson(checkpoint));

        TaskStatus next = stateMachine.transition(current, AgentEvent.USER_INPUT_RECEIVED);
        taskMapper.updateStatus(task.getId(), next.getCode());
        taskMapper.updateCheckpoint(task);

        Map<String, Object> payload = Map.of(
                "taskUuid", taskUuid,
                "pendingInputType", pendingInputType,
                "selectedCandidate", mergeSelectedCandidate(candidate, request),
                "selectedOrigin", checkpoint.getSelectedOrigin()
        );
        sseNotificationService.sendEvent(taskUuid, SseEvent.USER_SELECTION_CONFIRMED, payload);
        sseNotificationService.sendEvent(taskUuid, SseEvent.STATE_CHANGE, Map.of(
                "status", next.getCode(),
                "taskUuid", taskUuid,
                "pendingInputType", checkpoint.getPendingInputType()
        ));

        Task updated = taskMapper.findByUuid(taskUuid);
        return TaskResponse.from(updated, checkpoint);
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
        int dynamicTargetSteps = computeDynamicTargetSteps(totalAvailableMinutes, config, totalDays);
        config.setDynamicTargetSteps(dynamicTargetSteps);
        config.setAttractionsPerDay(Math.max(1, (int) Math.ceil((double) dynamicTargetSteps / totalDays)));

        TaskCheckpoint cp = new TaskCheckpoint();
        cp.setSchemaVersion("1.0");
        cp.setTaskId(task.getId());
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
        config.setAttractionsPerDay(Math.max(1, req.getAttractionsPerDay()));
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

    private int computeDynamicTargetSteps(int totalAvailableMinutes, PlanningConfig config, int totalDays) {
        int effectiveMinutes = Math.max(0, totalAvailableMinutes - config.getDestinationBufferMin());
        int estimatedSteps = Math.max(1, effectiveMinutes / ESTIMATED_MINUTES_PER_STOP);
        int perDayUpperBound = Math.max(1, Math.max(config.getAttractionsPerDay(), estimatedSteps / Math.max(1, totalDays)));
        return Math.max(1, Math.min(estimatedSteps, totalDays * Math.max(2, perDayUpperBound + 2)));
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
        String checkpointType = checkpoint.getPendingInputType();
        String requestType = request.getPendingInputType();
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
        if ("attraction_selection".equals(pendingInputType)) {
            return checkpoint.getRecommendationCandidates() == null ? List.of() : checkpoint.getRecommendationCandidates();
        }
        return List.of();
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
        selected.setName(request.getSelectedCandidateName());
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
        selected.setExplanations(candidate.getExplanations() == null ? List.of() : candidate.getExplanations());
        selected.setHighlights(candidate.getHighlights() == null ? List.of() : candidate.getHighlights());
        return selected;
    }
}
