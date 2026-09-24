package com.travelagent.service.routemap;

import com.fasterxml.jackson.core.type.TypeReference;
import com.travelagent.model.dto.PlanRouteMapResponse;
import com.travelagent.model.entity.PlanDayRouteMap;
import com.travelagent.util.JsonUtil;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 路线图响应组装器，负责把数据库实体转换为前端稳定 DTO。
 */
@Component
public class PlanRouteMapResponseAssembler {

    private final JsonUtil jsonUtil;
    private final PlanRouteMapAssetSupport assetSupport;

    @Value("${route-map.manual-regenerate-limit-per-record:3}")
    private int manualRegenerateLimit;

    /**
     * 创建路线图响应组装器。
     *
     * @param jsonUtil JSON 工具
     * @param assetSupport 路线图 OSS 资源辅助组件
     */
    public PlanRouteMapResponseAssembler(JsonUtil jsonUtil, PlanRouteMapAssetSupport assetSupport) {
        this.jsonUtil = jsonUtil;
        this.assetSupport = assetSupport;
    }

    /**
     * 组装尚未生成路线图时的占位响应。
     *
     * @param planId 计划 ID
     * @param dayNumber 天数
     * @param style 路线图风格
     * @return 未生成状态响应
     */
    public PlanRouteMapResponse notGenerated(Long planId, int dayNumber, String style) {
        PlanRouteMapResponse response = new PlanRouteMapResponse();
        response.setPlanId(planId);
        response.setDayNumber(dayNumber);
        response.setStyle(style);
        response.setStatus("not_generated");
        response.setProgressPercent(0);
        response.setManualRegenCount(0);
        response.setManualRegenerateLimit(manualRegenerateLimit);
        response.setManualRegenerateRemaining(manualRegenerateLimit);
        response.setStops(List.of());
        response.setSegments(List.of());
        return response;
    }

    /**
     * 将路线图实体转换为响应 DTO。
     *
     * @param routeMap 路线图实体
     * @return 前端响应 DTO
     */
    public PlanRouteMapResponse toResponse(PlanDayRouteMap routeMap) {
        PlanRouteMapResponse response = new PlanRouteMapResponse();
        response.setId(routeMap.getId());
        response.setPlanId(routeMap.getPlanId());
        response.setDayNumber(routeMap.getDayNumber());
        response.setStatus(routeMap.getStatus());
        response.setProgressPercent(routeMap.getProgressPercent());
        response.setStyle(routeMap.getStyle());
        response.setErrorCode(routeMap.getErrorCode());
        response.setErrorMessage(routeMap.getErrorMessage());
        response.setManualRegenCount(routeMap.getManualRegenCount() == null ? 0 : routeMap.getManualRegenCount());
        response.setManualRegenerateLimit(manualRegenerateLimit);
        response.setManualRegenerateRemaining(Math.max(0, manualRegenerateLimit - response.getManualRegenCount()));
        response.setUpdatedAt(routeMap.getUpdatedAt());
        response.setImageUrl(assetSupport.signedUrl(routeMap.getFinalOssKey()));
        response.setFallbackImageUrl(assetSupport.signedUrl(routeMap.getSkeletonOssKey()));
        response.setStops(readList(routeMap.getStopsJson()));
        response.setSegments(readList(routeMap.getSegmentsJson()));
        return response;
    }

    /**
     * 从 JSON 字符串安全读取列表字段。
     *
     * @param json JSON 字符串
     * @return 解析后的列表，失败时返回空列表
     */
    public List<Map<String, Object>> readList(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return jsonUtil.fromJson(json, new TypeReference<List<Map<String, Object>>>() {});
        } catch (Exception e) {
            return List.of();
        }
    }
}
