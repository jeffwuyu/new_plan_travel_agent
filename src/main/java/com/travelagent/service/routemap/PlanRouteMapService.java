package com.travelagent.service.routemap;

import com.fasterxml.jackson.core.type.TypeReference;
import com.travelagent.client.bailian.BailianImageClient;
import com.travelagent.client.bailian.BailianImageResult;
import com.travelagent.client.oss.OssClient;
import com.travelagent.exception.BusinessException;
import com.travelagent.mapper.PlanMapper;
import com.travelagent.mapper.PlanRouteMapJobMapper;
import com.travelagent.mapper.PlanRouteMapMapper;
import com.travelagent.model.dto.PlanRouteMapResponse;
import com.travelagent.model.entity.Plan;
import com.travelagent.model.entity.PlanDayRouteMap;
import com.travelagent.model.entity.PlanRouteMapJob;
import com.travelagent.model.entity.PlanStep;
import com.travelagent.util.JsonUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.context.event.EventListener;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 行程路线图服务，负责路线图记录查询、生成请求编排、后台作业恢复补偿和管理端统计。
 *
 * <p>配额检查、统计归一化、几何生成、图片渲染和 SSE 推送分别交给专门协作者完成。</p>
 */
@Service
public class PlanRouteMapService {

    private static final Set<String> RUNNING_STATUSES = Set.of(
            "pending", "generating", "geometry_ready", "skeleton_ready",
            "ai_submitted", "ai_generating", "ai_ready", "overlaying"
    );

    @Autowired private PlanMapper planMapper;
    @Autowired private PlanRouteMapMapper routeMapMapper;
    @Autowired(required = false) private PlanRouteMapJobMapper routeMapJobMapper;
    @Autowired private RouteGeometryService geometryService;
    @Autowired private RouteMapImageRenderer imageRenderer;
    @Autowired private RouteMapPromptBuilder promptBuilder;
    @Autowired private BailianImageClient bailianImageClient;
    @Autowired private OssClient ossClient;
    @Autowired private JsonUtil jsonUtil;
    @Autowired private PlanRouteMapSseService sseService;
    @Autowired private PlanRouteMapStatisticsNormalizer statisticsNormalizer;
    @Autowired private PlanRouteMapQuotaGuard quotaGuard;
    @Autowired private PlanRouteMapAssetSupport assetSupport;
    @Autowired private PlanRouteMapResponseAssembler responseAssembler;
    @Autowired private PlanRouteMapErrorClassifier errorClassifier;
    @Autowired private PlanRouteMapStateUpdater stateUpdater;

    @Autowired
    @Qualifier("routeMapExecutor")
    private ThreadPoolTaskExecutor executor;

    @Value("${route-map.default-style:anime_travel_map}")
    private String defaultStyle;

    @Value("${route-map.manual-regenerate-limit-per-record:3}")
    private int manualRegenerateLimit;

    @Value("${route-map.image-width:2048}")
    private int imageWidth;

    @Value("${route-map.image-height:1536}")
    private int imageHeight;

    @Value("${route-map.enabled:true}")
    private boolean routeMapEnabled;

    @Value("${dashscope.image.max-auto-retries:2}")
    private int maxAutoRetries;

    @Value("${route-map.max-recovery-attempts:3}")
    private int maxRecoveryAttempts;

    @Value("${route-map.dispatch-compensation.enabled:true}")
    private boolean dispatchCompensationEnabled;

    @Value("${route-map.dispatch-compensation.max-attempts:3}")
    private int dispatchCompensationMaxAttempts;

    @Value("${route-map.dispatch-compensation.retry-delay-seconds:60}")
    private long dispatchCompensationRetryDelaySeconds;

    @Value("${route-map.dispatch-compensation.scan-limit:20}")
    private int dispatchCompensationScanLimit;

    @Value("${route-map.job.lease-seconds:900}")
    private long routeMapJobLeaseSeconds;

    @EventListener(ApplicationReadyEvent.class)
    public void recoverRunningRouteMaps() {
        List<PlanDayRouteMap> running = routeMapMapper.findByStatuses(List.of(
                "pending", "generating", "geometry_ready", "skeleton_ready",
                "ai_submitted", "ai_generating", "ai_ready", "overlaying"));
        for (PlanDayRouteMap routeMap : running) {
            enqueueRecovery(routeMap);
        }
    }

    @Scheduled(
            fixedDelayString = "${route-map.dispatch-compensation.scan-interval-ms:60000}",
            initialDelayString = "${route-map.dispatch-compensation.scan-initial-delay-ms:60000}"
    )
    public void scheduledDispatchCompensation() {
        if (!dispatchCompensationEnabled) {
            return;
        }
        Map<String, Object> result = compensateDispatchFailures("scheduled");
        if (!result.isEmpty()) {
            // Keep this visible enough for operations without making normal scans noisy.
            int retried = ((Number) result.getOrDefault("retried", 0)).intValue();
            int exhausted = ((Number) result.getOrDefault("exhausted", 0)).intValue();
            if (retried > 0 || exhausted > 0) {
                org.slf4j.LoggerFactory.getLogger(PlanRouteMapService.class)
                        .info("Route map dispatch compensation result={}", result);
            }
        }
    }

    public List<PlanRouteMapResponse> list(Long planId, Long userId, String style) {
        assertPlanOwner(planId, userId);
        String normalizedStyle = style == null || style.isBlank() ? null : RouteMapStyles.normalize(style, defaultStyle);
        return routeMapMapper.findByPlanId(planId, normalizedStyle).stream()
                .map(this::toResponse)
                .toList();
    }

    public PlanRouteMapResponse getDay(Long planId, int dayNumber, Long userId, String style) {
        assertPlanOwner(planId, userId);
        String normalizedStyle = RouteMapStyles.normalize(style, defaultStyle);
        PlanDayRouteMap routeMap = routeMapMapper.findByPlanDayStyle(planId, dayNumber, normalizedStyle);
        if (routeMap == null) {
            return toResponse(planId, dayNumber, normalizedStyle);
        }
        return toResponse(routeMap);
    }

    @Transactional
    public PlanRouteMapResponse generateDay(Long planId, int dayNumber, Long userId, String style, boolean force) {
        if (!routeMapEnabled) {
            throw new BusinessException(400, "route map generation is disabled");
        }
        Plan plan = assertPlanOwner(planId, userId);
        String normalizedStyle = RouteMapStyles.normalize(style, defaultStyle);
        PlanDayRouteMap existing = routeMapMapper.findByPlanDayStyle(planId, dayNumber, normalizedStyle);
        if (existing != null) {
            if (RUNNING_STATUSES.contains(existing.getStatus())) {
                existing.setErrorCode("GENERATION_ALREADY_RUNNING");
                return toResponse(existing);
            }
            if (!force && ("succeeded".equals(existing.getStatus()) || "fallback".equals(existing.getStatus()))) {
                return toResponse(existing);
            }
            if (force && existing.getManualRegenCount() != null
                    && existing.getManualRegenCount() >= manualRegenerateLimit) {
                throw new BusinessException(429, "MANUAL_REGEN_LIMIT_EXCEEDED");
            }
            if (force) {
                routeMapMapper.incrementManualRegen(existing.getId());
                existing.setManualRegenCount((existing.getManualRegenCount() == null ? 0 : existing.getManualRegenCount()) + 1);
            }
            routeMapMapper.markRunning(existing.getId(), "pending", 0, null, null);
            existing.setStatus("pending");
            existing.setProgressPercent(0);
            if (!enqueueGeneration(existing.getId())) {
                PlanDayRouteMap failed = routeMapMapper.findById(existing.getId());
                return toResponse(failed == null ? existing : failed);
            }
            return toResponse(existing);
        }

        quotaGuard.assertDailyGenerateQuota(plan);

        PlanDayRouteMap routeMap = new PlanDayRouteMap();
        routeMap.setPlanId(planId);
        routeMap.setUserId(plan.getUserId());
        routeMap.setDayNumber(dayNumber);
        routeMap.setStyle(normalizedStyle);
        routeMap.setStatus("pending");
        routeMap.setProgressPercent(0);
        routeMap.setRetryCount(0);
        routeMap.setManualRegenCount(0);
        routeMapMapper.insert(routeMap);
        if (!enqueueGeneration(routeMap.getId())) {
            PlanDayRouteMap failed = routeMapMapper.findById(routeMap.getId());
            return toResponse(failed == null ? routeMap : failed);
        }
        return toResponse(routeMap);
    }

    public List<PlanRouteMapResponse> generateAll(Long planId, Long userId, String style, boolean force) {
        assertPlanOwner(planId, userId);
        String normalizedStyle = RouteMapStyles.normalize(style, defaultStyle);
        List<Integer> days = planMapper.findStepsByPlanId(planId).stream()
                .map(PlanStep::getDayNumber)
                .filter(day -> day != null && day > 0)
                .distinct()
                .sorted()
                .toList();
        List<PlanRouteMapResponse> responses = new ArrayList<>();
        for (Integer day : days) {
            responses.add(generateDay(planId, day, userId, normalizedStyle, force));
        }
        return responses;
    }

    public SseEmitter stream(Long planId, Long userId) {
        assertPlanOwner(planId, userId);
        SseEmitter emitter = sseService.createEmitter(planId, userId);
        try {
            synchronized (emitter) {
                emitter.send(SseEmitter.event()
                        .name("route_map_progress")
                        .data(Map.of("items", list(planId, userId, null))));
            }
        } catch (Exception ignored) {
            emitter.completeWithError(ignored);
        }
        return emitter;
    }

    public void triggerAutoGenerate(Long planId, Long userId) {
        try {
            generateAll(planId, userId, defaultStyle, false);
        } catch (Exception ignored) {
            // Route maps are best-effort and must not block plan completion.
        }
    }

    private boolean enqueueGeneration(Long routeMapId) {
        PlanRouteMapJob job = createRouteMapJob(routeMapId, "generation");
        if (job == null) {
            return enqueueGenerationDirect(routeMapId, null);
        }
        return enqueueGenerationDirect(routeMapId, job);
    }

    private boolean enqueueGenerationDirect(Long routeMapId, PlanRouteMapJob job) {
        try {
            executor.execute(() -> runGenerationJob(routeMapId, job == null ? null : job.getId()));
            return true;
        } catch (RuntimeException e) {
            markRouteMapJobFailed(job, "ROUTE_MAP_DISPATCH_FAILED", errorClassifier.message(e));
            stateUpdater.markDispatchFailed(routeMapId, "generation_dispatch", e);
            return false;
        }
    }

    private void enqueueRecovery(PlanDayRouteMap routeMap) {
        try {
            executor.execute(() -> recoverRouteMap(routeMap));
        } catch (RuntimeException e) {
            stateUpdater.markDispatchFailed(routeMap.getId(), "recovery_dispatch", e);
        }
    }

    private PlanRouteMapJob createRouteMapJob(Long routeMapId, String triggerType) {
        if (routeMapJobMapper == null) {
            return null;
        }
        PlanDayRouteMap routeMap = routeMapMapper.findById(routeMapId);
        if (routeMap == null) {
            return null;
        }
        PlanRouteMapJob job = new PlanRouteMapJob();
        job.setRouteMapId(routeMapId);
        job.setPlanId(routeMap.getPlanId());
        job.setUserId(routeMap.getUserId());
        job.setDayNumber(routeMap.getDayNumber());
        job.setStyle(routeMap.getStyle());
        job.setStatus("queued");
        job.setTriggerType(triggerType == null || triggerType.isBlank() ? "generation" : triggerType);
        job.setAttempts(0);
        job.setMaxAttempts(Math.max(1, dispatchCompensationMaxAttempts));
        job.setAvailableAt(LocalDateTime.now());
        routeMapJobMapper.insert(job);
        return job;
    }

    private void runGenerationJob(Long routeMapId, Long jobId) {
        PlanRouteMapJob job = null;
        if (routeMapJobMapper != null && jobId != null) {
            job = routeMapJobMapper.findById(jobId);
            if (job != null) {
                int claimed = routeMapJobMapper.markRunning(jobId, routeMapWorkerId(),
                        LocalDateTime.now().plusSeconds(Math.max(60L, routeMapJobLeaseSeconds)));
                if (claimed <= 0) {
                    return;
                }
                job = routeMapJobMapper.findById(jobId);
            }
        }
        try {
            runGeneration(routeMapId);
        } catch (RuntimeException e) {
            markRouteMapJobFailed(job, "ROUTE_MAP_JOB_EXECUTION_FAILED", errorClassifier.message(e));
            throw e;
        }
        PlanDayRouteMap latest = routeMapMapper.findById(routeMapId);
        if (job != null && routeMapJobMapper != null) {
            if (latest != null && ("failed".equals(latest.getStatus()) || "fallback".equals(latest.getStatus()))) {
                routeMapJobMapper.markDeadLetter(job.getId(),
                        latest.getErrorCode() == null || latest.getErrorCode().isBlank()
                                ? "ROUTE_MAP_GENERATION_FAILED"
                                : latest.getErrorCode(),
                        latest.getErrorMessage() == null || latest.getErrorMessage().isBlank()
                                ? "route map generation ended without a final image"
                                : latest.getErrorMessage());
            } else {
                routeMapJobMapper.markSucceeded(job.getId());
            }
        }
    }

    private void markRouteMapJobFailed(PlanRouteMapJob job, String errorCode, String errorMessage) {
        if (routeMapJobMapper == null || job == null || job.getId() == null) {
            return;
        }
        int attempts = job.getAttempts() == null ? 0 : job.getAttempts();
        int maxAttempts = job.getMaxAttempts() == null ? Math.max(1, dispatchCompensationMaxAttempts) : job.getMaxAttempts();
        String status = attempts >= maxAttempts ? "dead_letter" : "retrying";
        LocalDateTime availableAt = "dead_letter".equals(status)
                ? job.getAvailableAt()
                : LocalDateTime.now().plusSeconds(Math.max(0L, dispatchCompensationRetryDelaySeconds));
        routeMapJobMapper.markFailed(job.getId(), status,
                errorCode == null || errorCode.isBlank() ? "ROUTE_MAP_GENERATION_FAILED" : errorCode,
                errorMessage == null || errorMessage.isBlank() ? "route map generation failed" : errorMessage,
                availableAt);
    }

    private String routeMapWorkerId() {
        return "app-" + java.lang.management.ManagementFactory.getRuntimeMXBean().getName();
    }

    public Map<String, Object> adminRedispatch(Long routeMapId, Long adminUserId, String reason) {
        if (routeMapId == null) {
            throw new BusinessException(400, "routeMapId is required");
        }
        PlanDayRouteMap routeMap = routeMapMapper.findById(routeMapId);
        if (routeMap == null) {
            throw new BusinessException(404, "route map not found");
        }
        if (RUNNING_STATUSES.contains(routeMap.getStatus())) {
            throw new BusinessException(400, "route map generation is already running");
        }

        String previousStatus = routeMap.getStatus();
        String message = "Admin redispatch route map"
                + (reason == null || reason.isBlank() ? "" : ": " + reason.trim());
        routeMapMapper.markRunning(routeMapId, "pending", 0, null, message);
        routeMap.setStatus("pending");
        routeMap.setProgressPercent(0);
        routeMap.setErrorCode(null);
        routeMap.setErrorMessage(message);
        stateUpdater.push(routeMap, "pending", 0);

        boolean dispatched = enqueueGeneration(routeMapId);
        PlanDayRouteMap latest = routeMapMapper.findById(routeMapId);
        if (latest == null) {
            latest = routeMap;
        }

        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("routeMapId", routeMapId);
        result.put("planId", routeMap.getPlanId());
        result.put("dayNumber", routeMap.getDayNumber());
        result.put("style", routeMap.getStyle());
        result.put("previousStatus", previousStatus);
        result.put("status", latest.getStatus());
        result.put("adminUserId", adminUserId);
        result.put("reason", reason == null || reason.isBlank() ? "manual_route_map_redispatch" : reason.trim());
        result.put("dispatched", dispatched);
        return result;
    }

    public Map<String, Object> compensateDispatchFailures(String trigger) {
        Map<String, Object> summary = new java.util.LinkedHashMap<>();
        summary.put("enabled", dispatchCompensationEnabled);
        summary.put("trigger", trigger == null || trigger.isBlank() ? "manual" : trigger.trim());
        if (!dispatchCompensationEnabled) {
            summary.put("scanned", 0);
            summary.put("retried", 0);
            summary.put("exhausted", 0);
            return summary;
        }

        int limit = Math.max(1, dispatchCompensationScanLimit);
        int jobScanned = 0;
        int jobRetried = 0;
        if (routeMapJobMapper != null) {
            List<PlanRouteMapJob> jobs = routeMapJobMapper.findDispatchable(LocalDateTime.now(), limit);
            jobScanned = jobs.size();
            for (PlanRouteMapJob job : jobs) {
                PlanDayRouteMap routeMap = routeMapMapper.findById(job.getRouteMapId());
                if (routeMap == null) {
                    routeMapJobMapper.markDeadLetter(job.getId(), "ROUTE_MAP_NOT_FOUND",
                            "Route map record not found for job " + job.getId());
                    continue;
                }
                routeMapMapper.markRunning(routeMap.getId(), "pending", 0, null,
                        "Route map persistent job retry: " + summary.get("trigger"));
                routeMap.setStatus("pending");
                routeMap.setProgressPercent(0);
                routeMap.setErrorCode(null);
                routeMap.setErrorMessage("Route map persistent job retry: " + summary.get("trigger"));
                stateUpdater.push(routeMap, "pending", 0);
                if (enqueueGenerationDirect(routeMap.getId(), job)) {
                    jobRetried += 1;
                }
            }
        }

        LocalDateTime cutoff = LocalDateTime.now().minusSeconds(Math.max(0L, dispatchCompensationRetryDelaySeconds));
        List<PlanDayRouteMap> candidates = routeMapMapper.findFailedDispatchBefore(cutoff, limit);
        int retried = 0;
        int exhausted = 0;
        for (PlanDayRouteMap routeMap : candidates) {
            int nextAttempt = (routeMap.getRetryCount() == null ? 0 : routeMap.getRetryCount()) + 1;
            routeMapMapper.incrementRetryCount(routeMap.getId());
            routeMap.setRetryCount(nextAttempt);
            if (nextAttempt > Math.max(1, dispatchCompensationMaxAttempts)) {
                stateUpdater.complete(routeMap, "failed", routeMap.getProgressPercent() == null ? 0 : routeMap.getProgressPercent(),
                        routeMap.getAiRawOssKey(), routeMap.getFinalOssKey(),
                        "ROUTE_MAP_DISPATCH_RETRY_EXHAUSTED",
                        "Route map dispatch compensation exceeded max attempts: "
                                + Math.max(1, dispatchCompensationMaxAttempts),
                        System.currentTimeMillis());
                exhausted += 1;
                continue;
            }
            routeMapMapper.markRunning(routeMap.getId(), "pending", 0, null,
                    "Route map dispatch compensation retry: " + summary.get("trigger"));
            routeMap.setStatus("pending");
            routeMap.setProgressPercent(0);
            routeMap.setErrorCode(null);
            routeMap.setErrorMessage("Route map dispatch compensation retry: " + summary.get("trigger"));
            stateUpdater.push(routeMap, "pending", 0);
            if (enqueueGeneration(routeMap.getId())) {
                retried += 1;
            }
        }
        summary.put("scanned", candidates.size() + jobScanned);
        summary.put("retried", retried);
        summary.put("jobRetried", jobRetried);
        summary.put("exhausted", exhausted);
        summary.put("scanLimit", limit);
        summary.put("retryDelaySeconds", Math.max(0L, dispatchCompensationRetryDelaySeconds));
        summary.put("maxAttempts", Math.max(1, dispatchCompensationMaxAttempts));
        return summary;
    }

    public Map<String, Object> getAdminStatistics(LocalDate startDate, LocalDate endDate, int topLimit) {
        if (statisticsNormalizer == null) {
            statisticsNormalizer = new PlanRouteMapStatisticsNormalizer();
        }
        LocalDate normalizedEnd = endDate == null ? LocalDate.now() : endDate;
        LocalDate normalizedStart = startDate == null ? normalizedEnd.minusDays(6) : startDate;
        if (normalizedEnd.isBefore(normalizedStart)) {
            throw new BusinessException(400, "endDate must not be before startDate");
        }

        int limit = Math.min(Math.max(topLimit, 1), 100);
        LocalDateTime start = normalizedStart.atStartOfDay();
        LocalDateTime endExclusive = normalizedEnd.plusDays(1).atStartOfDay();

        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("startDate", normalizedStart.toString());
        result.put("endDate", normalizedEnd.toString());
        result.put("topLimit", limit);
        result.put("summary", statisticsNormalizer.normalizeSummary(routeMapMapper.summarizeBetween(start, endExclusive)));
        result.put("statusCounts", statisticsNormalizer.normalizeRows(routeMapMapper.countByStatusBetween(start, endExclusive)));
        result.put("failureErrorCodes",
                statisticsNormalizer.normalizeRows(
                        routeMapMapper.countFailuresByErrorCodeBetween(start, endExclusive, limit)));
        result.put("topUsers", statisticsNormalizer.normalizeRows(
                routeMapMapper.countByUserBetween(start, endExclusive, limit)));
        result.put("costAccountingStatus", "provider_billing_not_configured");
        result.put("costAccountingNote",
                "Local statistics count route-map records and failures; real Bailian billing requires provider configuration.");
        return result;
    }

    private void recoverRouteMap(PlanDayRouteMap routeMap) {
        PlanDayRouteMap latest = routeMapMapper.findById(routeMap.getId());
        if (latest == null) {
            return;
        }
        if ("succeeded".equals(latest.getStatus()) || "fallback".equals(latest.getStatus())) {
            return;
        }
        int recoveryAttempt = (latest.getRetryCount() == null ? 0 : latest.getRetryCount()) + 1;
        routeMapMapper.incrementRetryCount(latest.getId());
        latest.setRetryCount(recoveryAttempt);
        if (recoveryAttempt > maxRecoveryAttempts) {
            stateUpdater.complete(latest, "failed", latest.getProgressPercent() == null ? 0 : latest.getProgressPercent(),
                    latest.getAiRawOssKey(), latest.getFinalOssKey(),
                    "ROUTE_MAP_RECOVERY_EXHAUSTED",
                    "Route map recovery exceeded max attempts: " + maxRecoveryAttempts,
                    System.currentTimeMillis());
            return;
        }
        if (("ai_generating".equals(latest.getStatus()) || "ai_submitted".equals(latest.getStatus()))
                && latest.getTaskId() != null && !latest.getTaskId().isBlank()) {
            resumeAiTask(latest);
            return;
        }
        if (("ai_ready".equals(latest.getStatus()) || "overlaying".equals(latest.getStatus()))
                && latest.getAiRawOssKey() != null && !latest.getAiRawOssKey().isBlank()
                && latest.getRouteGeometryJson() != null && !latest.getRouteGeometryJson().isBlank()) {
            resumeOverlay(latest);
            return;
        }
        if (latest.getRouteGeometryJson() == null || latest.getSkeletonOssKey() == null) {
            routeMapMapper.markRunning(latest.getId(), "pending", 0, null, "Recovered route map generation after restart");
            latest.setStatus("pending");
            latest.setProgressPercent(0);
            latest.setErrorMessage("Recovered route map generation after restart");
            enqueueGeneration(latest.getId());
            return;
        }
        resumeFromSkeleton(latest);
    }

    private void resumeAiTask(PlanDayRouteMap routeMap) {
        try {
            BailianImageResult generated = bailianImageClient.waitForResult(routeMap.getTaskId());
            if (generated == null || generated.getImageUrl() == null || generated.getErrorCode() != null) {
                IllegalStateException failure = errorClassifier.bailianResultFailure(
                        generated, "Bailian task failed after restart");
                stateUpdater.complete(routeMap, "fallback", 100, routeMap.getAiRawOssKey(), routeMap.getFinalOssKey(),
                        errorClassifier.classifyBailianError(failure),
                        errorClassifier.message(failure),
                        System.currentTimeMillis());
                return;
            }
            byte[] raw = bailianImageClient.downloadImage(generated.getImageUrl());
            String rawKey = assetSupport.objectKey(routeMap, "ai-raw.png");
            ossClient.uploadObject(rawKey, raw, "image/png");
            routeMapMapper.updateAiRaw(routeMap.getId(), "ai_ready", 80, rawKey);
            routeMap.setAiRawOssKey(rawKey);
            routeMap.setStatus("ai_ready");
            routeMap.setProgressPercent(80);
            stateUpdater.push(routeMap, "ai_ready", 80);
            resumeOverlay(routeMap);
        } catch (Exception e) {
            stateUpdater.complete(routeMap, "fallback", 100, routeMap.getAiRawOssKey(), routeMap.getFinalOssKey(),
                    errorClassifier.classifyGenerationFailure(routeMap, "fallback", e),
                    errorClassifier.message(e), System.currentTimeMillis());
        }
    }

    private void resumeFromSkeleton(PlanDayRouteMap routeMap) {
        try {
            routeMapMapper.markRunning(routeMap.getId(), "ai_generating",
                    Math.max(60, routeMap.getProgressPercent() == null ? 60 : routeMap.getProgressPercent()),
                    routeMap.getErrorCode(), routeMap.getErrorMessage());
            routeMap.setStatus("ai_generating");
            routeMap.setProgressPercent(Math.max(60, routeMap.getProgressPercent() == null ? 60 : routeMap.getProgressPercent()));
            stateUpdater.push(routeMap, "ai_generating", routeMap.getProgressPercent());

            String skeletonUrl = assetSupport.signedUrlOrFail(routeMap.getSkeletonOssKey());
            List<Map<String, Object>> stops = responseAssembler.readList(routeMap.getStopsJson());
            String prompt = promptBuilder.build(routeMap.getStyle(), stops);
            BailianImageResult submit = bailianImageClient.submit(skeletonUrl, prompt);
            routeMapMapper.updateAiTask(routeMap.getId(), "ai_generating", 60,
                    bailianImageClient.getModel(), submit.getRequestId(), submit.getTaskId(),
                    routeMap.getRetryCount() == null ? 0 : routeMap.getRetryCount());
            routeMap.setModel(bailianImageClient.getModel());
            routeMap.setRequestId(submit.getRequestId());
            routeMap.setTaskId(submit.getTaskId());
            resumeAiTask(routeMap);
        } catch (Exception e) {
            stateUpdater.complete(routeMap, "fallback", 100, routeMap.getAiRawOssKey(), routeMap.getFinalOssKey(),
                    errorClassifier.classifyGenerationFailure(routeMap, "fallback", e),
                    errorClassifier.message(e), System.currentTimeMillis());
        }
    }

    private void resumeOverlay(PlanDayRouteMap routeMap) {
        try {
            routeMapMapper.markRunning(routeMap.getId(), "overlaying", 90, routeMap.getErrorCode(), routeMap.getErrorMessage());
            routeMap.setStatus("overlaying");
            routeMap.setProgressPercent(90);
            stateUpdater.push(routeMap, "overlaying", 90);
            byte[] raw = ossClient.downloadObject(routeMap.getAiRawOssKey());
            Map<String, Object> geometry = jsonUtil.fromJson(routeMap.getRouteGeometryJson(), new TypeReference<Map<String, Object>>() {});
            byte[] finalImage = imageRenderer.renderOverlay(raw, geometry, imageWidth, imageHeight);
            String finalKey = assetSupport.objectKey(routeMap, "final.png");
            ossClient.uploadObject(finalKey, finalImage, "image/png");
            stateUpdater.complete(routeMap, "succeeded", 100, routeMap.getAiRawOssKey(), finalKey, null, null, System.currentTimeMillis());
        } catch (Exception e) {
            stateUpdater.complete(routeMap, "fallback", 100, routeMap.getAiRawOssKey(), routeMap.getFinalOssKey(),
                    "IMAGE_OVERLAY_FAILED", errorClassifier.message(e), System.currentTimeMillis());
        }
    }

    private void runGeneration(Long routeMapId) {
        PlanDayRouteMap routeMap = routeMapMapper.findById(routeMapId);
        if (routeMap == null) {
            return;
        }
        long started = System.currentTimeMillis();
        try {
            stateUpdater.markRunning(routeMap, "generating", 5, null, null);
            Map<String, Object> geometry = geometryService.buildGeometry(routeMap.getPlanId(), routeMap.getDayNumber());
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> stops = (List<Map<String, Object>>) geometry.getOrDefault("stops", List.of());
            if (stops.isEmpty()) {
                stateUpdater.complete(routeMap, "failed", 0, null, null, "AMAP_GEOMETRY_FAILED", "No stops found", started);
                return;
            }
            routeMapMapper.updateGeometry(routeMap.getId(), "geometry_ready", 20,
                    jsonUtil.toJson(geometry), jsonUtil.toJson(geometry.get("stops")),
                    jsonUtil.toJson(geometry.get("segments")), jsonUtil.toJson(geometry.get("bounds")));
            routeMap.setRouteGeometryJson(jsonUtil.toJson(geometry));
            routeMap.setStopsJson(jsonUtil.toJson(geometry.get("stops")));
            routeMap.setSegmentsJson(jsonUtil.toJson(geometry.get("segments")));
            stateUpdater.push(routeMap, "geometry_ready", 20);

            byte[] skeleton = imageRenderer.renderSkeleton(geometry, imageWidth, imageHeight);
            String skeletonKey = assetSupport.objectKey(routeMap, "skeleton.png");
            ossClient.uploadObject(skeletonKey, skeleton, "image/png");
            routeMap.setSkeletonOssKey(skeletonKey);
            routeMapMapper.updateSkeleton(routeMap.getId(), "skeleton_ready", 35, skeletonKey);
            stateUpdater.push(routeMap, "skeleton_ready", 35);

            String skeletonUrl = assetSupport.signedUrlOrFail(skeletonKey);
            String prompt = promptBuilder.build(routeMap.getStyle(), stops);

            BailianImageResult submit = null;
            BailianImageResult generated = null;
            Exception lastError = null;
            int attempts = Math.max(1, maxAutoRetries + 1);
            for (int attempt = 1; attempt <= attempts; attempt += 1) {
                try {
                    submit = bailianImageClient.submit(skeletonUrl, prompt);
                    routeMapMapper.updateAiTask(routeMap.getId(), "ai_generating", 60,
                            bailianImageClient.getModel(), submit.getRequestId(), submit.getTaskId(), attempt - 1);
                    routeMap.setModel(bailianImageClient.getModel());
                    routeMap.setRequestId(submit.getRequestId());
                    routeMap.setTaskId(submit.getTaskId());
                    routeMap.setRetryCount(attempt - 1);
                    stateUpdater.push(routeMap, "ai_generating", 60);
                    generated = bailianImageClient.waitForResult(submit.getTaskId());
                    if (generated.getImageUrl() != null && generated.getErrorCode() == null) {
                        break;
                    }
                    lastError = errorClassifier.bailianResultFailure(generated, "Bailian task failed");
                } catch (Exception e) {
                    lastError = e;
                }
            }

            if (generated == null || generated.getImageUrl() == null) {
                stateUpdater.complete(routeMap, "fallback", 100, null, null,
                        errorClassifier.classifyBailianError(lastError),
                        errorClassifier.message(lastError), started);
                return;
            }

            byte[] raw = bailianImageClient.downloadImage(generated.getImageUrl());
            String rawKey = assetSupport.objectKey(routeMap, "ai-raw.png");
            ossClient.uploadObject(rawKey, raw, "image/png");
            routeMapMapper.updateAiRaw(routeMap.getId(), "ai_ready", 80, rawKey);
            routeMap.setAiRawOssKey(rawKey);
            routeMap.setStatus("ai_ready");
            routeMap.setProgressPercent(80);
            stateUpdater.push(routeMap, "ai_ready", 80);

            routeMapMapper.markRunning(routeMap.getId(), "overlaying", 90, null, null);
            routeMap.setStatus("overlaying");
            routeMap.setProgressPercent(90);
            stateUpdater.push(routeMap, "overlaying", 90);

            byte[] finalImage = imageRenderer.renderOverlay(raw, geometry, imageWidth, imageHeight);
            String finalKey = assetSupport.objectKey(routeMap, "final.png");
            ossClient.uploadObject(finalKey, finalImage, "image/png");
            stateUpdater.complete(routeMap, "succeeded", 100, rawKey, finalKey, null, null, started);
        } catch (Exception e) {
            String fallbackStatus = routeMap.getSkeletonOssKey() == null ? "failed" : "fallback";
            stateUpdater.complete(routeMap, fallbackStatus, fallbackStatus.equals("failed") ? 0 : 100,
                    null, null, errorClassifier.classifyGenerationFailure(routeMap, fallbackStatus, e),
                    errorClassifier.message(e), started);
        }
    }

    private PlanRouteMapResponse toResponse(PlanDayRouteMap routeMap) {
        return responseAssembler.toResponse(routeMap);
    }

    private PlanRouteMapResponse toResponse(Long planId, int dayNumber, String style) {
        return responseAssembler.notGenerated(planId, dayNumber, style);
    }

    private Plan assertPlanOwner(Long planId, Long userId) {
        Plan plan = planMapper.findById(planId);
        if (plan == null) {
            throw new BusinessException(404, "plan not found");
        }
        if (!plan.getUserId().equals(userId)) {
            throw new BusinessException(403, "no permission to access this plan");
        }
        return plan;
    }

}
