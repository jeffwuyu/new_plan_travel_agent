package com.travelagent.client.amap;

import java.util.List;
import java.util.Locale;

/**
 * 高德路线模式策略辅助类。
 *
 * <p>该类集中维护交通方式归一化、公交失败回退顺序和不同路线接口 URL 组装规则。</p>
 */
class AmapRouteModeSupport {

    private final String apiKey;
    private final String directionUrl;
    private final String walkingDirectionUrl;
    private final String bicyclingDirectionUrl;
    private final String transitDirectionUrl;

    /**
     * 创建路线模式辅助对象。
     *
     * @param apiKey 高德 API Key
     * @param directionUrl 驾车路线接口
     * @param walkingDirectionUrl 步行路线接口
     * @param bicyclingDirectionUrl 骑行路线接口
     * @param transitDirectionUrl 公交路线接口
     */
    AmapRouteModeSupport(String apiKey,
                         String directionUrl,
                         String walkingDirectionUrl,
                         String bicyclingDirectionUrl,
                         String transitDirectionUrl) {
        this.apiKey = apiKey;
        this.directionUrl = directionUrl;
        this.walkingDirectionUrl = walkingDirectionUrl;
        this.bicyclingDirectionUrl = bicyclingDirectionUrl;
        this.transitDirectionUrl = transitDirectionUrl;
    }

    /**
     * 根据用户出行方式生成高德路线尝试顺序。
     *
     * @param travelMode 用户请求的出行方式
     * @return 高德路线模式列表
     */
    List<String> resolveTravelModeSequence(String travelMode) {
        String normalized = normalizeTravelMode(travelMode);
        if ("walking".equals(normalized)) {
            return List.of("walking");
        }
        if ("transit".equals(normalized)) {
            return List.of("transit", "bicycling", "walking");
        }
        return List.of("driving");
    }

    /**
     * 将用户出行方式归一化为高德支持的路线模式。
     *
     * @param travelMode 用户请求的出行方式
     * @return 归一化后的路线模式
     */
    String normalizeTravelMode(String travelMode) {
        String normalized = travelMode == null ? "driving" : travelMode.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "walking", "transit", "bicycling" -> normalized;
            default -> "driving";
        };
    }

    /**
     * 判断当前失败路线模式是否允许继续尝试下一个模式。
     *
     * @param attemptedMode 已尝试模式
     * @param requestedMode 用户请求模式
     * @return 允许回退时返回 true
     */
    boolean shouldFallbackToNextMode(String attemptedMode, String requestedMode) {
        return "transit".equals(normalizeTravelMode(requestedMode))
                && ("transit".equals(attemptedMode) || "bicycling".equals(attemptedMode));
    }

    /**
     * 组装高德路线接口 URL。
     *
     * @param routeMode 高德路线模式
     * @param origin 起点坐标
     * @param destination 终点坐标
     * @return 可直接请求的 URL
     */
    String buildDirectionUrl(String routeMode, String origin, String destination) {
        return switch (routeMode) {
            case "walking" -> walkingDirectionUrl + "?key=" + apiKey
                    + "&origin=" + origin
                    + "&destination=" + destination;
            case "bicycling" -> bicyclingDirectionUrl + "?key=" + apiKey
                    + "&origin=" + origin
                    + "&destination=" + destination;
            case "transit" -> transitDirectionUrl + "?key=" + apiKey
                    + "&origin=" + origin
                    + "&destination=" + destination
                    + "&city=auto"
                    + "&strategy=0";
            default -> directionUrl + "?key=" + apiKey
                    + "&origin=" + origin
                    + "&destination=" + destination
                    + "&strategy=0";
        };
    }
}
