package com.travelagent.service.task.impl;

import com.travelagent.agent.context.DailyTimeWindow;
import com.travelagent.agent.context.PlanningConfig;
import com.travelagent.agent.context.RetryState;
import com.travelagent.agent.context.TaskCheckpoint;
import com.travelagent.agent.requirements.TravelConstraints;
import com.travelagent.agent.requirements.TravelRequirementParser;
import com.travelagent.client.amap.AmapClient;
import com.travelagent.exception.BusinessException;
import com.travelagent.model.dto.CreateTaskRequest;
import com.travelagent.model.dto.ResolvedLocation;
import com.travelagent.model.entity.Task;
import com.travelagent.model.enums.TaskStatus;
import com.travelagent.service.task.OriginCandidateService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 任务初始 checkpoint 构建器。
 *
 * <p>该组件集中处理创建任务时的结构化约束解析、规划配置组装、每日时间窗计算、
 * 动态目标步数估算和目的地解析，避免 `TaskServiceImpl#createTask` 承担过多初始化细节。</p>
 */
@Component
public class TaskInitialCheckpointBuilder {

    private static final Logger log = LoggerFactory.getLogger(TaskInitialCheckpointBuilder.class);
    private static final int ESTIMATED_MINUTES_PER_STOP = 150;

    @Autowired private OriginCandidateService originCandidateService;
    @Autowired private AmapClient amapClient;
    @Autowired(required = false) private TravelRequirementParser travelRequirementParser;

    /**
     * 校验创建任务请求中会影响 checkpoint 初始化的字段。
     *
     * @param request 创建任务请求
     */
    public void validateCreateRequest(CreateTaskRequest request) {
        if (request.getStartTime() == null || request.getEndTime() == null
                || !request.getEndTime().isAfter(request.getStartTime())) {
            throw new BusinessException(400, "end time must be later than start time");
        }
        if ((request.getFullDayStartTime() == null) != (request.getFullDayEndTime() == null)) {
            throw new BusinessException(400,
                    "full day start time and end time must be provided together");
        }
        if (request.getFullDayStartTime() != null
                && !request.getFullDayEndTime().isAfter(request.getFullDayStartTime())) {
            throw new BusinessException(400,
                    "full day end time must be later than full day start time");
        }
        if (request.getTotalBudgetYuan() == null
                || request.getTotalBudgetYuan().compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException(400, "total budget must be greater than 0");
        }
        if (request.getLodgingBudgetPerNightYuan() == null
                || request.getLodgingBudgetPerNightYuan().compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException(400,
                    "lodging budget per night must be greater than 0");
        }
        if (request.getAdultCount() != null && request.getAdultCount() <= 0) {
            throw new BusinessException(400, "adult count must be greater than 0");
        }
        if (request.getRoomCount() != null && request.getRoomCount() <= 0) {
            throw new BusinessException(400, "room count must be greater than 0");
        }
    }

    /**
     * 构建任务初始 checkpoint。
     *
     * @param task 已创建的任务实体
     * @param req 创建任务请求
     * @param maxPlanSteps 当前用户等级允许的最大计划步数
     * @return 可序列化保存的初始 checkpoint
     */
    public TaskCheckpoint build(Task task, CreateTaskRequest req, Integer maxPlanSteps) {
        int totalDays = calculateTotalDays(req.getStartTime(), req.getEndTime());
        TravelConstraints structuredConstraints = buildStructuredConstraints(req);
        PlanningConfig config = buildPlanningConfig(req, totalDays);
        config.setStructuredConstraints(structuredConstraints);
        List<DailyTimeWindow> dailyWindows = buildDailyWindows(config);
        int totalAvailableMinutes = totalAvailableMinutes(dailyWindows);
        int dynamicTargetSteps = computeDynamicTargetSteps(totalAvailableMinutes, config, maxPlanSteps);
        config.setDynamicTargetSteps(dynamicTargetSteps);

        TaskCheckpoint cp = new TaskCheckpoint();
        cp.setSchemaVersion(TaskCheckpoint.CURRENT_SCHEMA_VERSION);
        cp.setTaskId(task.getId());
        cp.setUserId(task.getUserId());
        cp.setTaskUuid(task.getTaskUuid());
        cp.setCurrentState(TaskStatus.PENDING.getCode());
        cp.setRegion(req.getRegion());
        cp.setProvinceName(req.getProvinceName());
        cp.setCityName(req.getCityName());
        cp.setDistrictName(req.getDistrictName());
        cp.setAdcode(req.getAdcode());
        cp.setUserIntent(req.getUserIntent());
        cp.setStructuredConstraints(structuredConstraints);
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
        cp.recordUserFeedback("original_request", req.getUserIntent(), buildOriginalRequestDetails(req));
        return cp;
    }

    /**
     * 解析用户自然语言偏好并合并显式表单约束。
     *
     * @param req 创建任务请求
     * @return 结构化旅行约束
     */
    private TravelConstraints buildStructuredConstraints(CreateTaskRequest req) {
        TravelRequirementParser parser = travelRequirementParser == null
                ? new TravelRequirementParser()
                : travelRequirementParser;
        TravelConstraints parsed = parser.parse(req.getUserIntent());
        return parsed.mergeFrom(parser.fromCreateTaskRequest(req));
    }

    /**
     * 根据创建请求组装规划配置。
     *
     * @param req 创建任务请求
     * @param totalDays 总行程天数
     * @return 规划配置
     */
    private PlanningConfig buildPlanningConfig(CreateTaskRequest req, int totalDays) {
        PlanningConfig config = new PlanningConfig();
        config.setTotalDays(totalDays);
        config.setPreferenceKeywords(req.getPreferenceKeywords());
        config.setTravelMode(req.getTravelMode());
        config.setProvinceName(req.getProvinceName());
        config.setCityName(req.getCityName());
        config.setDistrictName(req.getDistrictName());
        config.setAdcode(req.getAdcode());
        config.setStartLocationQuery(req.getStartLocationQuery());
        config.setEndLocationQuery(req.getEndLocationQuery());
        config.setStartTime(req.getStartTime());
        config.setEndTime(req.getEndTime());
        config.setFullDayStartTime(req.getFullDayStartTime());
        config.setFullDayEndTime(req.getFullDayEndTime());
        config.setTotalBudgetYuan(req.getTotalBudgetYuan());
        config.setLodgingBudgetPerNightYuan(req.getLodgingBudgetPerNightYuan());
        config.setAccommodationTypes(req.getAccommodationTypes() == null || req.getAccommodationTypes().isEmpty()
                ? List.of("hotel", "inn", "homestay")
                : req.getAccommodationTypes());
        config.setAdultCount(req.getAdultCount() == null ? 2 : req.getAdultCount());
        config.setRoomCount(req.getRoomCount() == null ? 1 : req.getRoomCount());
        return config;
    }

    /**
     * 计算行程覆盖的自然日数量。
     *
     * @param startTime 行程开始时间
     * @param endTime 行程结束时间
     * @return 总天数，首尾日期都计入
     */
    private int calculateTotalDays(LocalDateTime startTime, LocalDateTime endTime) {
        LocalDate startDate = startTime.toLocalDate();
        LocalDate endDate = endTime.toLocalDate();
        return (int) (Duration.between(startDate.atStartOfDay(), endDate.atStartOfDay()).toDays() + 1);
    }

    /**
     * 按首日、末日和完整日规则构建每日可规划时间窗。
     *
     * @param config 规划配置
     * @return 每日时间窗列表
     */
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

    /**
     * 汇总所有时间窗的可用分钟数。
     *
     * @param windows 每日时间窗
     * @return 总可用分钟数
     */
    private int totalAvailableMinutes(List<DailyTimeWindow> windows) {
        return windows.stream().mapToInt(DailyTimeWindow::availableMinutes).sum();
    }

    /**
     * 根据可用时间和用户等级上限估算目标景点步数。
     *
     * @param totalAvailableMinutes 总可用分钟数
     * @param config 规划配置
     * @param maxPlanSteps 用户等级允许的最大步数
     * @return 动态目标步数
     */
    private int computeDynamicTargetSteps(int totalAvailableMinutes, PlanningConfig config, Integer maxPlanSteps) {
        int effectiveMinutes = Math.max(0, totalAvailableMinutes - config.getDestinationBufferMin());
        int estimatedSteps = Math.max(1, effectiveMinutes / ESTIMATED_MINUTES_PER_STOP);
        int allowedSteps = maxPlanSteps == null || maxPlanSteps <= 0 ? estimatedSteps : maxPlanSteps;
        return Math.max(1, Math.min(estimatedSteps, allowedSteps));
    }

    /**
     * 解析终点坐标，失败时保留用户原始终点文本作为目的地。
     *
     * @param region 区域信息
     * @param endLocationQuery 终点查询文本
     * @return 目的地位置
     */
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

    /**
     * 组装原始请求摘要，写入 checkpoint 的用户反馈历史。
     *
     * @param req 创建任务请求
     * @return 原始请求摘要
     */
    private Map<String, Object> buildOriginalRequestDetails(CreateTaskRequest req) {
        Map<String, Object> originalRequestDetails = new LinkedHashMap<>();
        originalRequestDetails.put("region", req.getRegion());
        originalRequestDetails.put("startTime", req.getStartTime());
        originalRequestDetails.put("endTime", req.getEndTime());
        return originalRequestDetails;
    }
}
