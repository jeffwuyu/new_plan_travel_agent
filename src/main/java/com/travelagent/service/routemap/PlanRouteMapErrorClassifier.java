package com.travelagent.service.routemap;

import com.travelagent.client.bailian.BailianImageResult;
import com.travelagent.model.entity.PlanDayRouteMap;
import org.springframework.stereotype.Component;

/**
 * 路线图错误分类器，负责把百炼、OSS 和本地渲染异常映射为稳定错误码。
 */
@Component
public class PlanRouteMapErrorClassifier {

    /**
     * 根据百炼接口异常内容分类错误码。
     *
     * @param error 百炼提交或轮询异常
     * @return 面向接口和管理端展示的错误码
     */
    public String classifyBailianError(Exception error) {
        String text = message(error).toUpperCase();
        if (text.contains("401") || text.contains("403")
                || text.contains("UNAUTHORIZED") || text.contains("FORBIDDEN")
                || text.contains("AUTH") || text.contains("ACCESSDENIED")
                || text.contains("ACCESS DENIED") || text.contains("INVALID_API_KEY")
                || text.contains("API KEY") || text.contains("API-KEY")
                || text.contains("NOT CONFIGURED")) {
            return "BAILIAN_AUTH_FAILED";
        }
        if (text.contains("TIMEOUT")) {
            return "BAILIAN_TIMEOUT";
        }
        if (text.contains("SAFETY") || text.contains("RISK")
                || text.contains("INSPECTION") || text.contains("SENSITIVE")
                || text.contains("VIOLATION") || text.contains("POLICY")) {
            return "BAILIAN_SAFETY_BLOCKED";
        }
        return "BAILIAN_SUBMIT_FAILED";
    }

    /**
     * 根据生成阶段和降级状态分类路线图生成失败。
     *
     * @param routeMap 路线图记录
     * @param fallbackStatus 失败后写入的状态
     * @param error 原始异常
     * @return 稳定错误码
     */
    public String classifyGenerationFailure(PlanDayRouteMap routeMap, String fallbackStatus, Exception error) {
        String text = message(error).toUpperCase();
        if (text.contains("SIGNED URL") || text.contains("SIGN URL") || text.contains("SIGNATURE")) {
            return "OSS_SIGN_URL_FAILED";
        }
        if ("failed".equals(fallbackStatus) || routeMap.getSkeletonOssKey() == null) {
            return "OSS_UPLOAD_FAILED";
        }
        return classifyBailianError(error);
    }

    /**
     * 把百炼结果中的错误字段包装为异常，便于复用统一分类逻辑。
     *
     * @param result 百炼异步任务结果
     * @param fallback 缺省错误消息
     * @return 包含百炼错误详情的异常
     */
    public IllegalStateException bailianResultFailure(BailianImageResult result, String fallback) {
        if (result == null) {
            return new IllegalStateException(fallback);
        }
        return new IllegalStateException(firstNonBlank(
                joinError(result.getErrorCode(), result.getErrorMessage()),
                firstNonBlank(result.getErrorMessage(), firstNonBlank(result.getErrorCode(), fallback))));
    }

    /**
     * 提取异常消息，缺失时返回路线图默认失败文案。
     *
     * @param error 原始异常
     * @return 可记录到任务表的错误消息
     */
    public String message(Exception error) {
        return error == null || error.getMessage() == null ? "route map generation failed" : error.getMessage();
    }

    /**
     * 拼接错误码和错误详情。
     *
     * @param code 错误码
     * @param detail 错误详情
     * @return 非空错误描述
     */
    private String joinError(String code, String detail) {
        if ((code == null || code.isBlank()) && (detail == null || detail.isBlank())) {
            return null;
        }
        if (code == null || code.isBlank()) {
            return detail;
        }
        if (detail == null || detail.isBlank()) {
            return code;
        }
        return code + ": " + detail;
    }

    /**
     * 返回第一个非空字符串。
     *
     * @param value 优先值
     * @param fallback 兜底值
     * @return 非空字符串或兜底值
     */
    private String firstNonBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
