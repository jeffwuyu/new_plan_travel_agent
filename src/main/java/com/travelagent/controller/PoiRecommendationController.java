package com.travelagent.controller;

import com.travelagent.filter.JwtAuthInterceptor;
import com.travelagent.model.dto.NearbyPoiRecommendationRequest;
import com.travelagent.model.dto.NearbyPoiRecommendationResponse;
import com.travelagent.model.dto.Result;
import com.travelagent.service.recommendation.NearbyPoiRecommendationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "景点推荐", description = "相近景点推荐接口")
@RestController
@RequestMapping("/api/recommendations")
public class PoiRecommendationController {

    @Autowired private NearbyPoiRecommendationService nearbyPoiRecommendationService;

    /**
     * 处理recommendNearby。
     * @param request 请求参数
     * @param httpRequest HTTP请求对象
     * @return 返回统一封装后的响应结果。
     */
    @Operation(summary = "推荐相近景点")
    @PostMapping("/nearby")
    public Result<NearbyPoiRecommendationResponse> recommendNearby(@Valid @RequestBody NearbyPoiRecommendationRequest request,
                                                                   HttpServletRequest httpRequest) {
        // Reuse existing auth guard conventions for secured endpoints.
        JwtAuthInterceptor.getUserId(httpRequest);
        return Result.success(nearbyPoiRecommendationService.recommend(request));
    }
}
