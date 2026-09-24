package com.travelagent.controller;

import com.travelagent.filter.JwtAuthInterceptor;
import com.travelagent.model.dto.PlanRouteMapResponse;
import com.travelagent.model.dto.Result;
import com.travelagent.model.dto.RouteMapGenerateRequest;
import com.travelagent.service.routemap.PlanRouteMapService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/plans/{planId}/route-maps")
public class PlanRouteMapController {

    @Autowired
    private PlanRouteMapService routeMapService;

    @GetMapping
    public Result<Map<String, Object>> list(@PathVariable Long planId,
                                            @RequestParam(required = false) String style,
                                            HttpServletRequest request) {
        Long userId = JwtAuthInterceptor.getUserId(request);
        return Result.success(Map.of("items", routeMapService.list(planId, userId, style)));
    }

    @GetMapping("/{dayNumber}")
    public Result<PlanRouteMapResponse> getDay(@PathVariable Long planId,
                                               @PathVariable Integer dayNumber,
                                               @RequestParam(required = false) String style,
                                               HttpServletRequest request) {
        Long userId = JwtAuthInterceptor.getUserId(request);
        return Result.success(routeMapService.getDay(planId, dayNumber, userId, style));
    }

    @PostMapping("/{dayNumber}/generate")
    public Result<PlanRouteMapResponse> generateDay(@PathVariable Long planId,
                                                    @PathVariable Integer dayNumber,
                                                    @RequestParam(defaultValue = "false") boolean force,
                                                    @RequestBody(required = false) RouteMapGenerateRequest body,
                                                    HttpServletRequest request) {
        Long userId = JwtAuthInterceptor.getUserId(request);
        String style = body == null ? null : body.getStyle();
        boolean effectiveForce = force || (body != null && Boolean.TRUE.equals(body.getForce()));
        return Result.success(routeMapService.generateDay(planId, dayNumber, userId, style, effectiveForce));
    }

    @PostMapping("/generate-all")
    public Result<List<PlanRouteMapResponse>> generateAll(@PathVariable Long planId,
                                                          @RequestBody(required = false) RouteMapGenerateRequest body,
                                                          HttpServletRequest request) {
        Long userId = JwtAuthInterceptor.getUserId(request);
        String style = body == null ? null : body.getStyle();
        boolean force = body != null && Boolean.TRUE.equals(body.getForce());
        return Result.success(routeMapService.generateAll(planId, userId, style, force));
    }

    @GetMapping("/stream")
    public SseEmitter stream(@PathVariable Long planId, HttpServletRequest request) {
        Long userId = JwtAuthInterceptor.getUserId(request);
        return routeMapService.stream(planId, userId);
    }
}
