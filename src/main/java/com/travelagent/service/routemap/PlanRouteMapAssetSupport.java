package com.travelagent.service.routemap;

import com.travelagent.client.oss.OssClient;
import com.travelagent.model.entity.PlanDayRouteMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 路线图 OSS 资源辅助组件，统一生成对象 key 和签名访问 URL。
 */
@Component
public class PlanRouteMapAssetSupport {

    private final OssClient ossClient;

    @Value("${oss.route-map-prefix:route-maps/}")
    private String routeMapPrefix;

    @Value("${oss.signed-url-ttl-minutes:30}")
    private long signedUrlTtlMinutes;

    /**
     * 创建路线图资源辅助组件。
     *
     * @param ossClient OSS 客户端
     */
    public PlanRouteMapAssetSupport(OssClient ossClient) {
        this.ossClient = ossClient;
    }

    /**
     * 为路线图生成标准 OSS 对象 key。
     *
     * @param routeMap 路线图记录
     * @param filename 文件名
     * @return OSS 对象 key
     */
    public String objectKey(PlanDayRouteMap routeMap, String filename) {
        String normalizedPrefix = routeMapPrefix == null || routeMapPrefix.isBlank()
                ? ""
                : (routeMapPrefix.endsWith("/") ? routeMapPrefix : routeMapPrefix + "/");
        return normalizedPrefix
                + routeMap.getPlanId()
                + "/day-" + routeMap.getDayNumber()
                + "/" + routeMap.getStyle()
                + "/" + filename;
    }

    /**
     * 生成签名 URL，失败时返回 null，适合响应 DTO 的可选图片字段。
     *
     * @param ossKey OSS 对象 key
     * @return 签名 URL，失败或 key 为空时返回 null
     */
    public String signedUrl(String ossKey) {
        if (ossKey == null || ossKey.isBlank()) {
            return null;
        }
        try {
            return signedUrlOrFail(ossKey);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 生成签名 URL，失败时抛出异常，适合生成流程必须拿到 URL 的阶段。
     *
     * @param ossKey OSS 对象 key
     * @return 签名 URL
     */
    public String signedUrlOrFail(String ossKey) {
        try {
            return ossClient.generateSignedUrl(ossKey, Duration.ofMinutes(signedUrlTtlMinutes));
        } catch (Exception e) {
            throw new IllegalStateException("OSS signed URL generation failed: " + message(e), e);
        }
    }

    /**
     * 提取异常消息，避免空消息进入业务错误。
     *
     * @param error 异常
     * @return 可读错误消息
     */
    private String message(Exception error) {
        return error == null || error.getMessage() == null ? "unknown error" : error.getMessage();
    }
}
