package com.travelagent.service.routemap;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.travelagent.client.bailian.BailianImageClient;
import com.travelagent.client.bailian.BailianImageResult;
import com.travelagent.client.oss.OssClient;
import com.travelagent.exception.BusinessException;
import com.travelagent.mapper.PlanMapper;
import com.travelagent.mapper.PlanRouteMapMapper;
import com.travelagent.mapper.UserMapper;
import com.travelagent.mapper.UserQuotaConfigMapper;
import com.travelagent.model.dto.PlanRouteMapResponse;
import com.travelagent.model.entity.Plan;
import com.travelagent.model.entity.PlanDayRouteMap;
import com.travelagent.model.entity.User;
import com.travelagent.model.entity.UserQuotaConfig;
import com.travelagent.util.JsonUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doThrow;

@ExtendWith(MockitoExtension.class)
@DisplayName("PlanRouteMapService Tests")
class PlanRouteMapServiceTest {

    @Mock private PlanMapper planMapper;
    @Mock private PlanRouteMapMapper routeMapMapper;
    @Mock private UserMapper userMapper;
    @Mock private UserQuotaConfigMapper quotaConfigMapper;
    @Mock private RouteGeometryService geometryService;
    @Mock private RouteMapImageRenderer imageRenderer;
    @Mock private RouteMapPromptBuilder promptBuilder;
    @Mock private BailianImageClient bailianImageClient;
    @Mock private OssClient ossClient;
    @Mock private PlanRouteMapSseService sseService;
    @Mock private ThreadPoolTaskExecutor executor;

    @Spy private JsonUtil jsonUtil = new JsonUtil();

    @InjectMocks private PlanRouteMapQuotaGuard quotaGuard;
    private PlanRouteMapAssetSupport assetSupport;
    private PlanRouteMapResponseAssembler responseAssembler;
    private PlanRouteMapErrorClassifier errorClassifier;
    private PlanRouteMapStateUpdater stateUpdater;
    @InjectMocks private PlanRouteMapService service;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "defaultStyle", "anime_travel_map");
        ReflectionTestUtils.setField(service, "manualRegenerateLimit", 3);
        ReflectionTestUtils.setField(quotaGuard, "dailyGenerateLimitPerUser", 20);
        ReflectionTestUtils.setField(service, "quotaGuard", quotaGuard);
        ReflectionTestUtils.setField(service, "imageWidth", 512);
        ReflectionTestUtils.setField(service, "imageHeight", 512);
        ReflectionTestUtils.setField(service, "routeMapEnabled", true);
        assetSupport = new PlanRouteMapAssetSupport(ossClient);
        responseAssembler = new PlanRouteMapResponseAssembler(jsonUtil, assetSupport);
        errorClassifier = new PlanRouteMapErrorClassifier();
        stateUpdater = new PlanRouteMapStateUpdater(routeMapMapper, sseService, responseAssembler, errorClassifier);
        ReflectionTestUtils.setField(assetSupport, "routeMapPrefix", "route-maps/");
        ReflectionTestUtils.setField(assetSupport, "signedUrlTtlMinutes", 30L);
        ReflectionTestUtils.setField(responseAssembler, "manualRegenerateLimit", 3);
        ReflectionTestUtils.setField(service, "assetSupport", assetSupport);
        ReflectionTestUtils.setField(service, "responseAssembler", responseAssembler);
        ReflectionTestUtils.setField(service, "errorClassifier", errorClassifier);
        ReflectionTestUtils.setField(service, "stateUpdater", stateUpdater);
        ReflectionTestUtils.setField(service, "maxAutoRetries", 1);
        ReflectionTestUtils.setField(service, "maxRecoveryAttempts", 3);
        ReflectionTestUtils.setField(service, "dispatchCompensationEnabled", true);
        ReflectionTestUtils.setField(service, "dispatchCompensationMaxAttempts", 3);
        ReflectionTestUtils.setField(service, "dispatchCompensationRetryDelaySeconds", 0L);
        ReflectionTestUtils.setField(service, "dispatchCompensationScanLimit", 20);
        ReflectionTestUtils.setField(jsonUtil, "objectMapper", new ObjectMapper());
        User user = new User();
        user.setId(7L);
        user.setUserLevel(1);
        lenient().when(userMapper.findById(7L)).thenReturn(user);
        lenient().when(quotaConfigMapper.findByUserLevel(1)).thenReturn(null);
        lenient().doAnswer(invocation -> {
            Runnable task = invocation.getArgument(0);
            task.run();
            return null;
        }).when(executor).execute(any(Runnable.class));
    }

    @Test
    @DisplayName("recoverRunningRouteMaps resumes ai_generating records by polling existing Bailian task")
    void recoverRunningRouteMaps_resumesAiGeneratingTask() {
        PlanDayRouteMap routeMap = runningRouteMap("ai_generating");
        routeMap.setTaskId("task-123");
        routeMap.setRouteGeometryJson(geometryJson());
        routeMap.setSkeletonOssKey("route-maps/88/day-1/anime_travel_map/skeleton.png");

        when(routeMapMapper.findByStatuses(any())).thenReturn(List.of(routeMap));
        when(routeMapMapper.findById(routeMap.getId())).thenReturn(routeMap);
        when(bailianImageClient.waitForResult("task-123"))
                .thenReturn(new BailianImageResult("req-1", "task-123", "https://img.example/raw.png", "SUCCEEDED", null, null));
        when(ossClient.downloadObject("route-maps/88/day-1/anime_travel_map/ai-raw.png")).thenReturn(new byte[]{1, 2, 3});
        when(bailianImageClient.downloadImage("https://img.example/raw.png")).thenReturn(new byte[]{9, 8, 7});
        when(imageRenderer.renderOverlay(any(), any(), anyInt(), anyInt())).thenReturn(new byte[]{3, 2, 1});

        service.recoverRunningRouteMaps();

        verify(bailianImageClient).waitForResult("task-123");
        verify(routeMapMapper).updateAiRaw(eq(routeMap.getId()), eq("ai_ready"), eq(80), eq("route-maps/88/day-1/anime_travel_map/ai-raw.png"));
        verify(routeMapMapper).updateCompleted(eq(routeMap.getId()), eq("succeeded"), eq(100),
                eq("route-maps/88/day-1/anime_travel_map/ai-raw.png"),
                eq("route-maps/88/day-1/anime_travel_map/final.png"),
                eq(null), eq(null), anyLong());
    }

    @Test
    @DisplayName("recoverRunningRouteMaps fails records that exceed recovery attempts")
    void recoverRunningRouteMaps_exhaustedRecoveryAttempts_marksFailed() {
        PlanDayRouteMap routeMap = runningRouteMap("ai_generating");
        routeMap.setRetryCount(3);
        routeMap.setTaskId("task-123");
        routeMap.setRouteGeometryJson(geometryJson());
        routeMap.setSkeletonOssKey("route-maps/88/day-1/anime_travel_map/skeleton.png");

        when(routeMapMapper.findByStatuses(any())).thenReturn(List.of(routeMap));
        when(routeMapMapper.findById(routeMap.getId())).thenReturn(routeMap);

        service.recoverRunningRouteMaps();

        verify(routeMapMapper).incrementRetryCount(routeMap.getId());
        verify(bailianImageClient, never()).waitForResult(any());
        verify(ossClient, never()).downloadObject(any());
        verify(routeMapMapper).updateCompleted(eq(routeMap.getId()), eq("failed"), eq(60),
                eq(null), eq(null), eq("ROUTE_MAP_RECOVERY_EXHAUSTED"),
                eq("Route map recovery exceeded max attempts: 3"), anyLong());
    }

    @Test
    @DisplayName("recoverRunningRouteMaps resumes overlaying records from stored ai raw image")
    void recoverRunningRouteMaps_resumesOverlayingTask() {
        PlanDayRouteMap routeMap = runningRouteMap("overlaying");
        routeMap.setAiRawOssKey("route-maps/88/day-1/anime_travel_map/ai-raw.png");
        routeMap.setRouteGeometryJson(geometryJson());

        when(routeMapMapper.findByStatuses(any())).thenReturn(List.of(routeMap));
        when(routeMapMapper.findById(routeMap.getId())).thenReturn(routeMap);
        when(ossClient.downloadObject(routeMap.getAiRawOssKey())).thenReturn(new byte[]{4, 5, 6});
        when(imageRenderer.renderOverlay(any(), any(), anyInt(), anyInt())).thenReturn(new byte[]{6, 5, 4});

        service.recoverRunningRouteMaps();

        verify(bailianImageClient, never()).waitForResult(any());
        verify(routeMapMapper).markRunning(eq(routeMap.getId()), eq("overlaying"), eq(90), eq(null), eq(null));
        verify(routeMapMapper).updateCompleted(eq(routeMap.getId()), eq("succeeded"), eq(100),
                eq("route-maps/88/day-1/anime_travel_map/ai-raw.png"),
                eq("route-maps/88/day-1/anime_travel_map/final.png"),
                eq(null), eq(null), anyLong());
    }

    @Test
    @DisplayName("recoverRunningRouteMaps requeues pending records without geometry or skeleton")
    void recoverRunningRouteMaps_requeuesPendingWork() {
        PlanDayRouteMap routeMap = runningRouteMap("pending");
        when(routeMapMapper.findByStatuses(any())).thenReturn(List.of(routeMap));
        when(routeMapMapper.findById(routeMap.getId())).thenReturn(routeMap);
        when(geometryService.buildGeometry(anyLong(), anyInt())).thenReturn(Map.of("stops", List.of()));

        service.recoverRunningRouteMaps();

        verify(routeMapMapper).markRunning(eq(routeMap.getId()), eq("pending"), eq(0), eq(null),
                eq("Recovered route map generation after restart"));
        verify(routeMapMapper).updateCompleted(eq(routeMap.getId()), eq("failed"), eq(0),
                eq(null), eq(null), eq("AMAP_GEOMETRY_FAILED"), eq("No stops found"), anyLong());
    }

    @Test
    @DisplayName("recoverRunningRouteMaps resumes skeleton-ready records by resubmitting Bailian task")
    void recoverRunningRouteMaps_resubmitsFromSkeletonReady() {
        PlanDayRouteMap routeMap = runningRouteMap("skeleton_ready");
        routeMap.setRouteGeometryJson(geometryJson());
        routeMap.setStopsJson(stopsJson());
        routeMap.setSkeletonOssKey("route-maps/88/day-1/anime_travel_map/skeleton.png");

        when(routeMapMapper.findByStatuses(any())).thenReturn(List.of(routeMap));
        when(routeMapMapper.findById(routeMap.getId())).thenReturn(routeMap);
        when(ossClient.generateSignedUrl(eq(routeMap.getSkeletonOssKey()), any())).thenReturn("https://oss.example/skeleton.png");
        when(promptBuilder.build(eq(routeMap.getStyle()), any())).thenReturn("travel poster prompt");
        when(bailianImageClient.getModel()).thenReturn("wanx2.1");
        when(bailianImageClient.submit("https://oss.example/skeleton.png", "travel poster prompt"))
                .thenReturn(new BailianImageResult("req-2", "task-234", null, "RUNNING", null, null));
        when(bailianImageClient.waitForResult("task-234"))
                .thenReturn(new BailianImageResult("req-2", "task-234", "https://img.example/final-raw.png", "SUCCEEDED", null, null));
        when(bailianImageClient.downloadImage("https://img.example/final-raw.png")).thenReturn(new byte[]{8, 8, 8});
        when(imageRenderer.renderOverlay(any(), any(), anyInt(), anyInt())).thenReturn(new byte[]{7, 7, 7});

        service.recoverRunningRouteMaps();

        verify(routeMapMapper).updateAiTask(eq(routeMap.getId()), eq("ai_generating"), eq(60),
                eq("wanx2.1"), eq("req-2"), eq("task-234"), eq(1));
        verify(routeMapMapper).updateAiRaw(eq(routeMap.getId()), eq("ai_ready"), eq(80),
                eq("route-maps/88/day-1/anime_travel_map/ai-raw.png"));
        verify(routeMapMapper).updateCompleted(eq(routeMap.getId()), eq("succeeded"), eq(100),
                eq("route-maps/88/day-1/anime_travel_map/ai-raw.png"),
                eq("route-maps/88/day-1/anime_travel_map/final.png"),
                eq(null), eq(null), anyLong());
    }

    @Test
    @DisplayName("recoverRunningRouteMaps classifies signed URL failures while resuming from skeleton")
    void recoverRunningRouteMaps_signedUrlFailure_usesOssSignUrlErrorCode() {
        PlanDayRouteMap routeMap = runningRouteMap("skeleton_ready");
        routeMap.setRouteGeometryJson(geometryJson());
        routeMap.setStopsJson(stopsJson());
        routeMap.setSkeletonOssKey("route-maps/88/day-1/anime_travel_map/skeleton.png");

        when(routeMapMapper.findByStatuses(any())).thenReturn(List.of(routeMap));
        when(routeMapMapper.findById(routeMap.getId())).thenReturn(routeMap);
        when(ossClient.generateSignedUrl(eq(routeMap.getSkeletonOssKey()), any()))
                .thenThrow(new IllegalStateException("signature service unavailable"));

        service.recoverRunningRouteMaps();

        verify(routeMapMapper).updateCompleted(eq(routeMap.getId()), eq("fallback"), eq(100),
                eq(null), eq(null), eq("OSS_SIGN_URL_FAILED"),
                eq("OSS signed URL generation failed: signature service unavailable"), anyLong());
        verify(bailianImageClient, never()).submit(any(), any());
    }

    @Test
    @DisplayName("generateDay classifies skeleton signed URL failures separately")
    void generateDay_signedUrlFailure_usesOssSignUrlErrorCode() {
        mockNewRouteMap();
        when(geometryService.buildGeometry(88L, 1)).thenReturn(geometry());
        when(imageRenderer.renderSkeleton(any(), anyInt(), anyInt())).thenReturn(new byte[]{1, 1, 1});
        when(ossClient.generateSignedUrl(eq("route-maps/88/day-1/anime_travel_map/skeleton.png"), any()))
                .thenThrow(new IllegalStateException("signature service unavailable"));

        service.generateDay(88L, 1, 7L, "anime_travel_map", false);

        verify(routeMapMapper).updateCompleted(eq(12L), eq("fallback"), eq(100),
                eq(null), eq(null), eq("OSS_SIGN_URL_FAILED"),
                eq("OSS signed URL generation failed: signature service unavailable"), anyLong());
        verify(bailianImageClient, never()).submit(any(), any());
    }

    @Test
    @DisplayName("generateDay classifies Bailian auth failures separately")
    void generateDay_bailianAuthFailure_usesBailianAuthErrorCode() {
        mockNewRouteMap();
        mockSkeletonReady();
        when(promptBuilder.build(eq("anime_travel_map"), any())).thenReturn("travel poster prompt");
        when(bailianImageClient.submit("https://oss.example/skeleton.png", "travel poster prompt"))
                .thenThrow(new IllegalStateException("Bailian image API returned HTTP 401: Unauthorized"));

        service.generateDay(88L, 1, 7L, "anime_travel_map", false);

        verify(routeMapMapper).updateCompleted(eq(12L), eq("fallback"), eq(100),
                eq(null), eq(null), eq("BAILIAN_AUTH_FAILED"),
                eq("Bailian image API returned HTTP 401: Unauthorized"), anyLong());
    }

    @Test
    @DisplayName("generateDay classifies Bailian safety blocks separately")
    void generateDay_bailianSafetyBlock_usesBailianSafetyErrorCode() {
        mockNewRouteMap();
        mockSkeletonReady();
        when(promptBuilder.build(eq("anime_travel_map"), any())).thenReturn("travel poster prompt");
        when(bailianImageClient.getModel()).thenReturn("wanx2.1");
        when(bailianImageClient.submit("https://oss.example/skeleton.png", "travel poster prompt"))
                .thenReturn(new BailianImageResult("req-3", "task-345", null, "RUNNING", null, null));
        when(bailianImageClient.waitForResult("task-345"))
                .thenReturn(new BailianImageResult("req-3", "task-345", null, "FAILED",
                        "DataInspectionFailed", "content safety risk detected"));

        service.generateDay(88L, 1, 7L, "anime_travel_map", false);

        verify(routeMapMapper).updateCompleted(eq(12L), eq("fallback"), eq(100),
                eq(null), eq(null), eq("BAILIAN_SAFETY_BLOCKED"),
                eq("DataInspectionFailed: content safety risk detected"), anyLong());
    }

    @Test
    @DisplayName("generateDay blocks new route maps when daily route map quota is exhausted")
    void generateDay_newRecord_dailyLimitExceeded_throwsBusinessException() {
        when(planMapper.findById(88L)).thenReturn(plan());
        when(routeMapMapper.findByPlanDayStyle(88L, 1, "anime_travel_map")).thenReturn(null);
        when(routeMapMapper.countCreatedByUserBetween(eq(7L), any(), any())).thenReturn(20);

        assertThatThrownBy(() -> service.generateDay(88L, 1, 7L, "anime_travel_map", false))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("ROUTE_MAP_DAILY_LIMIT_EXCEEDED");

        verify(routeMapMapper, never()).insert(any(PlanDayRouteMap.class));
        verify(executor, never()).execute(any());
    }

    @Test
    @DisplayName("generateDay uses per-user-level route map daily quota when configured")
    void generateDay_newRecord_userLevelRouteMapLimitExceeded_throwsBusinessException() {
        UserQuotaConfig config = new UserQuotaConfig();
        config.setUserLevel(1);
        config.setRouteMapDailyLimit(5);
        when(quotaConfigMapper.findByUserLevel(1)).thenReturn(config);
        when(planMapper.findById(88L)).thenReturn(plan());
        when(routeMapMapper.findByPlanDayStyle(88L, 1, "anime_travel_map")).thenReturn(null);
        when(routeMapMapper.countCreatedByUserBetween(eq(7L), any(), any())).thenReturn(5);

        assertThatThrownBy(() -> service.generateDay(88L, 1, 7L, "anime_travel_map", false))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("ROUTE_MAP_DAILY_LIMIT_EXCEEDED");

        verify(routeMapMapper, never()).insert(any(PlanDayRouteMap.class));
        verify(executor, never()).execute(any());
    }

    @Test
    @DisplayName("generateDay does not apply daily new-record quota to existing manual regenerate")
    void generateDay_existingForceRegenerate_skipsDailyNewRecordQuota() {
        PlanDayRouteMap existing = runningRouteMap("failed");
        existing.setManualRegenCount(1);
        when(planMapper.findById(88L)).thenReturn(plan());
        when(routeMapMapper.findByPlanDayStyle(88L, 1, "anime_travel_map")).thenReturn(existing);

        service.generateDay(88L, 1, 7L, "anime_travel_map", true);

        verify(routeMapMapper, never()).countCreatedByUserBetween(any(), any(), any());
        verify(routeMapMapper).incrementManualRegen(existing.getId());
        verify(routeMapMapper).markRunning(existing.getId(), "pending", 0, null, null);
    }

    @Test
    @DisplayName("generateDay marks dispatch failures as failed with a dedicated error code")
    void generateDay_dispatchFailure_marksFailed() {
        mockNewRouteMap();
        PlanDayRouteMap failed = runningRouteMap("failed");
        failed.setProgressPercent(0);
        failed.setErrorCode("ROUTE_MAP_DISPATCH_FAILED");
        failed.setErrorMessage("Route map dispatch failed during generation_dispatch: executor rejected");
        when(routeMapMapper.findById(12L)).thenReturn(failed);
        doThrow(new RuntimeException("executor rejected"))
                .when(executor).execute(any(Runnable.class));

        PlanRouteMapResponse response = service.generateDay(88L, 1, 7L, "anime_travel_map", false);

        org.assertj.core.api.Assertions.assertThat(response.getStatus()).isEqualTo("failed");
        org.assertj.core.api.Assertions.assertThat(response.getErrorCode()).isEqualTo("ROUTE_MAP_DISPATCH_FAILED");
        verify(routeMapMapper).updateCompleted(eq(12L), eq("failed"), eq(0),
                eq(null), eq(null), eq("ROUTE_MAP_DISPATCH_FAILED"),
                eq("Route map dispatch failed during generation_dispatch: executor rejected"), anyLong());
    }

    @Test
    @DisplayName("adminRedispatch resets failed route map and submits it again")
    void adminRedispatch_failedRecord_resetsAndDispatches() {
        PlanDayRouteMap existing = runningRouteMap("failed");
        existing.setErrorCode("ROUTE_MAP_DISPATCH_FAILED");
        existing.setErrorMessage("executor rejected");
        when(routeMapMapper.findById(12L)).thenReturn(existing);
        doAnswer(invocation -> null).when(executor).execute(any(Runnable.class));

        Map<String, Object> result = service.adminRedispatch(12L, 99L, "manual fix");

        org.assertj.core.api.Assertions.assertThat(result.get("routeMapId")).isEqualTo(12L);
        org.assertj.core.api.Assertions.assertThat(result.get("previousStatus")).isEqualTo("failed");
        org.assertj.core.api.Assertions.assertThat(result.get("status")).isEqualTo("pending");
        org.assertj.core.api.Assertions.assertThat(result.get("dispatched")).isEqualTo(true);
        verify(routeMapMapper).markRunning(12L, "pending", 0, null, "Admin redispatch route map: manual fix");
        verify(executor).execute(any(Runnable.class));
    }

    @Test
    @DisplayName("adminRedispatch rejects already running route maps")
    void adminRedispatch_runningRecord_throwsBusinessException() {
        when(routeMapMapper.findById(12L)).thenReturn(runningRouteMap("generating"));

        assertThatThrownBy(() -> service.adminRedispatch(12L, 99L, "manual fix"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("already running");

        verify(executor, never()).execute(any(Runnable.class));
    }

    @Test
    @DisplayName("compensateDispatchFailures retries failed dispatch jobs")
    void compensateDispatchFailures_retriesFailedDispatchJobs() {
        PlanDayRouteMap failed = runningRouteMap("failed");
        failed.setRetryCount(1);
        failed.setErrorCode("ROUTE_MAP_DISPATCH_FAILED");
        failed.setErrorMessage("executor rejected");
        when(routeMapMapper.findFailedDispatchBefore(any(), eq(20))).thenReturn(List.of(failed));
        doAnswer(invocation -> null).when(executor).execute(any(Runnable.class));

        Map<String, Object> result = service.compensateDispatchFailures("manual");

        org.assertj.core.api.Assertions.assertThat(result.get("scanned")).isEqualTo(1);
        org.assertj.core.api.Assertions.assertThat(result.get("retried")).isEqualTo(1);
        org.assertj.core.api.Assertions.assertThat(result.get("exhausted")).isEqualTo(0);
        verify(routeMapMapper).incrementRetryCount(12L);
        verify(routeMapMapper).markRunning(12L, "pending", 0, null,
                "Route map dispatch compensation retry: manual");
        verify(executor).execute(any(Runnable.class));
    }

    @Test
    @DisplayName("compensateDispatchFailures marks exhausted jobs when retry limit is exceeded")
    void compensateDispatchFailures_exhausted_marksFailed() {
        PlanDayRouteMap failed = runningRouteMap("failed");
        failed.setRetryCount(3);
        failed.setErrorCode("ROUTE_MAP_DISPATCH_FAILED");
        failed.setErrorMessage("executor rejected");
        when(routeMapMapper.findFailedDispatchBefore(any(), eq(20))).thenReturn(List.of(failed));

        Map<String, Object> result = service.compensateDispatchFailures("manual");

        org.assertj.core.api.Assertions.assertThat(result.get("scanned")).isEqualTo(1);
        org.assertj.core.api.Assertions.assertThat(result.get("retried")).isEqualTo(0);
        org.assertj.core.api.Assertions.assertThat(result.get("exhausted")).isEqualTo(1);
        verify(routeMapMapper).incrementRetryCount(12L);
        verify(routeMapMapper).updateCompleted(eq(12L), eq("failed"), eq(60),
                eq(null), eq(null), eq("ROUTE_MAP_DISPATCH_RETRY_EXHAUSTED"),
                eq("Route map dispatch compensation exceeded max attempts: 3"), anyLong());
        verify(executor, never()).execute(any(Runnable.class));
    }

    @Test
    @DisplayName("getAdminStatistics normalizes route map statistics")
    void getAdminStatistics_returnsNormalizedStats() {
        when(routeMapMapper.summarizeBetween(any(), any())).thenReturn(Map.of(
                "TOTALCOUNT", 5L,
                "SUCCEEDEDCOUNT", 3L,
                "FAILEDCOUNT", 1L,
                "FALLBACKCOUNT", 1L,
                "RUNNINGCOUNT", 0L,
                "MANUALREGENERATECOUNT", 2L,
                "AVERAGELATENCYMS", 1200.5
        ));
        when(routeMapMapper.countByStatusBetween(any(), any())).thenReturn(List.of(
                Map.of("STATUS", "succeeded", "COUNT", 3L)
        ));
        when(routeMapMapper.countFailuresByErrorCodeBetween(any(), any(), eq(3))).thenReturn(List.of(
                Map.of("ERRORCODE", "BAILIAN_TIMEOUT", "COUNT", 1L)
        ));
        when(routeMapMapper.countByUserBetween(any(), any(), eq(3))).thenReturn(List.of(
                Map.of("USERID", 7L, "COUNT", 4L, "SUCCEEDEDCOUNT", 3L)
        ));

        Map<String, Object> result = service.getAdminStatistics(
                LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 7), 3);

        Map<String, Object> summary = (Map<String, Object>) result.get("summary");
        org.assertj.core.api.Assertions.assertThat(summary.get("totalCount")).isEqualTo(5L);
        org.assertj.core.api.Assertions.assertThat(summary.get("averageLatencyMs")).isEqualTo(1200.5);
        List<Map<String, Object>> failureErrorCodes = (List<Map<String, Object>>) result.get("failureErrorCodes");
        org.assertj.core.api.Assertions.assertThat(failureErrorCodes.get(0))
                .containsEntry("errorCode", "BAILIAN_TIMEOUT")
                .containsEntry("count", 1L);
        List<Map<String, Object>> topUsers = (List<Map<String, Object>>) result.get("topUsers");
        org.assertj.core.api.Assertions.assertThat(topUsers.get(0))
                .containsEntry("userId", 7L)
                .containsEntry("count", 4L);
        org.assertj.core.api.Assertions.assertThat(result.get("costAccountingStatus"))
                .isEqualTo("provider_billing_not_configured");
    }

    @Test
    @DisplayName("getDay exposes manual regenerate quota for missing records")
    void getDay_notGenerated_exposesManualRegenerateQuota() {
        Plan plan = plan();
        when(planMapper.findById(88L)).thenReturn(plan);
        when(routeMapMapper.findByPlanDayStyle(88L, 1, "anime_travel_map")).thenReturn(null);

        PlanRouteMapResponse response = service.getDay(88L, 1, 7L, "anime_travel_map");

        org.assertj.core.api.Assertions.assertThat(response.getStatus()).isEqualTo("not_generated");
        org.assertj.core.api.Assertions.assertThat(response.getManualRegenCount()).isEqualTo(0);
        org.assertj.core.api.Assertions.assertThat(response.getManualRegenerateLimit()).isEqualTo(3);
        org.assertj.core.api.Assertions.assertThat(response.getManualRegenerateRemaining()).isEqualTo(3);
    }

    @Test
    @DisplayName("getDay exposes manual regenerate remaining attempts for existing records")
    void getDay_existingRecord_exposesManualRegenerateRemaining() {
        Plan plan = plan();
        PlanDayRouteMap routeMap = runningRouteMap("fallback");
        routeMap.setManualRegenCount(2);
        routeMap.setErrorCode("BAILIAN_TIMEOUT");
        routeMap.setErrorMessage("Bailian image generation polling timed out");
        routeMap.setSkeletonOssKey("route-maps/88/day-1/anime_travel_map/skeleton.png");

        when(planMapper.findById(88L)).thenReturn(plan);
        when(routeMapMapper.findByPlanDayStyle(88L, 1, "anime_travel_map")).thenReturn(routeMap);
        when(ossClient.generateSignedUrl(eq(routeMap.getSkeletonOssKey()), any())).thenReturn("https://oss.example/skeleton.png");

        PlanRouteMapResponse response = service.getDay(88L, 1, 7L, "anime_travel_map");

        org.assertj.core.api.Assertions.assertThat(response.getErrorCode()).isEqualTo("BAILIAN_TIMEOUT");
        org.assertj.core.api.Assertions.assertThat(response.getManualRegenCount()).isEqualTo(2);
        org.assertj.core.api.Assertions.assertThat(response.getManualRegenerateLimit()).isEqualTo(3);
        org.assertj.core.api.Assertions.assertThat(response.getManualRegenerateRemaining()).isEqualTo(1);
    }

    private PlanDayRouteMap runningRouteMap(String status) {
        PlanDayRouteMap routeMap = new PlanDayRouteMap();
        routeMap.setId(12L);
        routeMap.setPlanId(88L);
        routeMap.setUserId(7L);
        routeMap.setDayNumber(1);
        routeMap.setStyle("anime_travel_map");
        routeMap.setStatus(status);
        routeMap.setProgressPercent(60);
        return routeMap;
    }

    private void mockNewRouteMap() {
        when(planMapper.findById(88L)).thenReturn(plan());
        when(routeMapMapper.findByPlanDayStyle(88L, 1, "anime_travel_map")).thenReturn(null);
        when(routeMapMapper.countCreatedByUserBetween(eq(7L), any(), any())).thenReturn(0);
        doAnswer(invocation -> {
            PlanDayRouteMap routeMap = invocation.getArgument(0);
            routeMap.setId(12L);
            return 1;
        }).when(routeMapMapper).insert(any(PlanDayRouteMap.class));
        when(routeMapMapper.findById(12L)).thenAnswer(invocation -> {
            PlanDayRouteMap routeMap = runningRouteMap("pending");
            routeMap.setProgressPercent(0);
            return routeMap;
        });
    }

    private void mockSkeletonReady() {
        when(geometryService.buildGeometry(88L, 1)).thenReturn(geometry());
        when(imageRenderer.renderSkeleton(any(), anyInt(), anyInt())).thenReturn(new byte[]{1, 1, 1});
        when(ossClient.generateSignedUrl(eq("route-maps/88/day-1/anime_travel_map/skeleton.png"), any()))
                .thenReturn("https://oss.example/skeleton.png");
    }

    private Map<String, Object> geometry() {
        return Map.of(
                "stops", List.of(Map.of("order", 1, "name", "Bell Tower", "lng", 108.95, "lat", 34.26)),
                "segments", List.of(),
                "bounds", Map.of("minLng", 108.94, "maxLng", 108.96, "minLat", 34.25, "maxLat", 34.27)
        );
    }

    private String geometryJson() {
        return jsonUtil.toJson(Map.of(
                "stops", List.of(Map.of("order", 1, "name", "Bell Tower", "lng", 108.95, "lat", 34.26)),
                "segments", List.of(),
                "bounds", Map.of("minLng", 108.94, "maxLng", 108.96, "minLat", 34.25, "maxLat", 34.27)
        ));
    }

    private String stopsJson() {
        return jsonUtil.toJson(List.of(
                Map.of("order", 1, "name", "Bell Tower", "lng", 108.95, "lat", 34.26)
        ));
    }

    private Plan plan() {
        Plan plan = new Plan();
        plan.setId(88L);
        plan.setUserId(7L);
        return plan;
    }
}
