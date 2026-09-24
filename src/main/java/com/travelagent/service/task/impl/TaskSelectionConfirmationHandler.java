package com.travelagent.service.task.impl;

import com.travelagent.agent.context.TaskCheckpoint;
import com.travelagent.exception.BusinessException;
import com.travelagent.model.dto.ConfirmOriginSelectionRequest;
import com.travelagent.model.dto.LocationCandidateItem;
import com.travelagent.model.dto.ResolvedLocation;
import com.travelagent.model.dto.SelectionOptionItem;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 任务用户选择确认处理器，封装起点、分支和景点候选确认的 checkpoint 更新规则。
 */
@Component
public class TaskSelectionConfirmationHandler {

    /**
     * 应用用户选择确认，并返回后续 SSE 需要使用的确认结果。
     *
     * @param checkpoint 任务 checkpoint
     * @param request 选择确认请求
     * @return 选择确认结果
     */
    public SelectionConfirmationResult applySelection(TaskCheckpoint checkpoint,
                                                      ConfirmOriginSelectionRequest request) {
        String pendingInputType = resolvePendingInputType(checkpoint, request);
        LocationCandidateItem candidate = null;

        if ("origin_selection".equals(pendingInputType)) {
            candidate = resolveCandidate(checkpoint, request, pendingInputType);
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
                || "route_candidate_selection".equals(pendingInputType)) {
            candidate = resolveCandidate(checkpoint, request, pendingInputType);
            checkpoint.setSelectedAttractionCandidate(mergeSelectedCandidate(candidate, request));
        } else {
            throw new BusinessException(400, "unsupported pending input type: " + pendingInputType);
        }

        return new SelectionConfirmationResult(pendingInputType, candidate);
    }

    /**
     * 清理选择相关临时状态，并设置任务准备恢复执行。
     *
     * @param checkpoint 任务 checkpoint
     * @param pendingInputType 已处理的输入类型
     * @param resumingStatus 恢复状态值
     */
    public void clearSelectionState(TaskCheckpoint checkpoint, String pendingInputType, String resumingStatus) {
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
        checkpoint.setCurrentState(resumingStatus);
        checkpoint.setPauseReason(null);
        checkpoint.setResumableAt(null);
    }

    /**
     * 构建用户选择确认后的 SSE 事件载荷。
     *
     * @param taskUuid 任务唯一标识
     * @param checkpoint 任务 checkpoint
     * @param result 选择确认结果
     * @param request 选择确认请求
     * @return SSE 事件载荷
     */
    public Map<String, Object> buildSelectionConfirmedPayload(String taskUuid,
                                                              TaskCheckpoint checkpoint,
                                                              SelectionConfirmationResult result,
                                                              ConfirmOriginSelectionRequest request) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("taskUuid", taskUuid);
        payload.put("pendingInputType", result.pendingInputType());
        if (result.candidate() != null && !"origin_selection".equals(result.pendingInputType())) {
            payload.put("selectedCandidate", mergeSelectedCandidate(result.candidate(), request));
        }
        if (checkpoint.getSelectedBranchType() != null) {
            payload.put("selectedBranchType", checkpoint.getSelectedBranchType());
        }
        if (checkpoint.getSelectedOrigin() != null) {
            payload.put("selectedOrigin", checkpoint.getSelectedOrigin());
        }
        return payload;
    }

    /**
     * 从确认请求中解析待处理输入类型。
     *
     * @param checkpoint 任务 checkpoint
     * @param request 选择确认请求
     * @return 待处理输入类型
     */
    private String resolvePendingInputType(TaskCheckpoint checkpoint, ConfirmOriginSelectionRequest request) {
        String requestType = request.getPendingInputType();
        String checkpointType = checkpoint.getPendingInputType();
        if (requestType == null || requestType.isBlank()) {
            return checkpointType;
        }
        if (checkpointType != null && !checkpointType.isBlank() && !checkpointType.equals(requestType)) {
            throw new BusinessException(400, "pending input type mismatch");
        }
        return requestType;
    }

    /**
     * 解析用户选中的候选。
     *
     * @param checkpoint 任务 checkpoint
     * @param request 选择确认请求
     * @param pendingInputType 待处理输入类型
     * @return 用户选中的候选
     */
    private LocationCandidateItem resolveCandidate(TaskCheckpoint checkpoint,
                                                   ConfirmOriginSelectionRequest request,
                                                   String pendingInputType) {
        List<LocationCandidateItem> pendingCandidates = resolvePendingCandidates(checkpoint, pendingInputType);
        if (pendingCandidates.isEmpty()) {
            throw new BusinessException(400, "no pending candidates available");
        }
        return pendingCandidates.stream()
                .filter(item -> item.getCandidateId().equals(request.getSelectedCandidateId()))
                .findFirst()
                .orElseThrow(() -> new BusinessException(400, "selected candidate does not belong to this task"));
    }

    /**
     * 根据待处理输入类型读取对应候选列表。
     *
     * @param checkpoint 任务 checkpoint
     * @param pendingInputType 待处理输入类型
     * @return 当前可选择候选
     */
    private List<LocationCandidateItem> resolvePendingCandidates(TaskCheckpoint checkpoint, String pendingInputType) {
        if ("origin_selection".equals(pendingInputType)) {
            return checkpoint.getLocationCandidates() == null ? List.of() : checkpoint.getLocationCandidates();
        }
        if ("attraction_selection".equals(pendingInputType)
                || "route_candidate_selection".equals(pendingInputType)) {
            return checkpoint.getRecommendationCandidates() == null ? List.of() : checkpoint.getRecommendationCandidates();
        }
        return List.of();
    }

    /**
     * 从待选分支中解析用户选择的选项。
     *
     * @param checkpoint 任务 checkpoint
     * @param selectedOptionId 用户选择的选项 ID
     * @return 分支选项
     */
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

    /**
     * 将起点候选和用户确认信息转换为已解析地点。
     *
     * @param candidate 候选项
     * @param request 选择确认请求
     * @return 已解析地点
     */
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

    /**
     * 合并候选原始信息和用户确认坐标，形成 checkpoint 中的已选候选。
     *
     * @param candidate 候选项
     * @param request 选择确认请求
     * @return 已选候选快照
     */
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

    /**
     * 用户选择确认结果。
     *
     * @param pendingInputType 已处理的输入类型
     * @param candidate 已确认候选；分支选择时为空
     */
    public record SelectionConfirmationResult(String pendingInputType, LocationCandidateItem candidate) {
    }
}
