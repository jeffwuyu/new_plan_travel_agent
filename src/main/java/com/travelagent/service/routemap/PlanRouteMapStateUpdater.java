package com.travelagent.service.routemap;

import com.travelagent.mapper.PlanRouteMapMapper;
import com.travelagent.model.dto.PlanRouteMapResponse;
import com.travelagent.model.entity.PlanDayRouteMap;
import org.springframework.stereotype.Component;

/**
 * 路线图状态更新器，集中处理状态落库、本地实体同步和 SSE 推送。
 */
@Component
public class PlanRouteMapStateUpdater {

    private final PlanRouteMapMapper routeMapMapper;
    private final PlanRouteMapSseService sseService;
    private final PlanRouteMapResponseAssembler responseAssembler;
    private final PlanRouteMapErrorClassifier errorClassifier;

    /**
     * 创建路线图状态更新器。
     *
     * @param routeMapMapper 路线图 Mapper
     * @param sseService 路线图 SSE 服务
     * @param responseAssembler 响应组装器
     * @param errorClassifier 错误分类器
     */
    public PlanRouteMapStateUpdater(PlanRouteMapMapper routeMapMapper,
                                    PlanRouteMapSseService sseService,
                                    PlanRouteMapResponseAssembler responseAssembler,
                                    PlanRouteMapErrorClassifier errorClassifier) {
        this.routeMapMapper = routeMapMapper;
        this.sseService = sseService;
        this.responseAssembler = responseAssembler;
        this.errorClassifier = errorClassifier;
    }

    /**
     * 标记路线图进入运行中状态，并推送进度。
     *
     * @param routeMap 路线图记录
     * @param status 目标状态
     * @param progress 进度百分比
     * @param code 错误码
     * @param message 错误或进度说明
     */
    public void markRunning(PlanDayRouteMap routeMap, String status, int progress, String code, String message) {
        routeMap.setStatus(status);
        routeMap.setProgressPercent(progress);
        routeMap.setErrorCode(code);
        routeMap.setErrorMessage(message);
        routeMapMapper.markRunning(routeMap.getId(), status, progress, code, message);
        push(routeMap, status, progress);
    }

    /**
     * 完成路线图生成，并记录最终资源、错误和耗时。
     *
     * @param routeMap 路线图记录
     * @param status 最终状态
     * @param progress 进度百分比
     * @param aiRawKey AI 原图 OSS Key
     * @param finalKey 最终图 OSS Key
     * @param errorCode 错误码
     * @param errorMessage 错误消息
     * @param started 开始时间戳
     */
    public void complete(PlanDayRouteMap routeMap, String status, int progress, String aiRawKey, String finalKey,
                         String errorCode, String errorMessage, long started) {
        routeMap.setStatus(status);
        routeMap.setProgressPercent(progress);
        routeMap.setAiRawOssKey(aiRawKey);
        routeMap.setFinalOssKey(finalKey);
        routeMap.setErrorCode(errorCode);
        routeMap.setErrorMessage(errorMessage);
        routeMap.setLatencyMs(System.currentTimeMillis() - started);
        routeMapMapper.updateCompleted(routeMap.getId(), status, progress, aiRawKey, finalKey,
                errorCode, errorMessage, routeMap.getLatencyMs());
        push(routeMap, status, progress);
    }

    /**
     * 将派发失败记录为终态失败，便于后续补偿扫描识别。
     *
     * @param routeMapId 路线图记录 ID
     * @param trigger 派发触发来源
     * @param error 派发异常
     */
    public void markDispatchFailed(Long routeMapId, String trigger, RuntimeException error) {
        PlanDayRouteMap routeMap = routeMapMapper.findById(routeMapId);
        if (routeMap == null) {
            return;
        }
        String message = "Route map dispatch failed"
                + (trigger == null || trigger.isBlank() ? "" : " during " + trigger)
                + ": " + errorClassifier.message(error);
        complete(routeMap, "failed", 0, routeMap.getAiRawOssKey(), routeMap.getFinalOssKey(),
                "ROUTE_MAP_DISPATCH_FAILED", message, System.currentTimeMillis());
    }

    /**
     * 推送路线图进度事件。
     *
     * @param routeMap 路线图记录
     * @param status 当前状态
     * @param progress 当前进度
     */
    public void push(PlanDayRouteMap routeMap, String status, int progress) {
        routeMap.setStatus(status);
        routeMap.setProgressPercent(progress);
        PlanRouteMapResponse response = responseAssembler.toResponse(routeMap);
        sseService.send(routeMap.getPlanId(), response);
    }
}
