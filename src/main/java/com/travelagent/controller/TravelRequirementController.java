package com.travelagent.controller;

import com.travelagent.filter.JwtAuthInterceptor;
import com.travelagent.model.dto.Result;
import com.travelagent.model.dto.TravelRequirementRequests;
import com.travelagent.service.task.TravelRequirementDraftService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/travel-requirements")
public class TravelRequirementController {
    private final TravelRequirementDraftService service;

    public TravelRequirementController(TravelRequirementDraftService service) { this.service = service; }

    @PostMapping
    public Result<TravelRequirementRequests.Response> create(@Valid @RequestBody TravelRequirementRequests.Create request,
                                                              HttpServletRequest http) {
        return Result.success(service.create(user(http), JwtAuthInterceptor.getUserLevel(http), request, http.getRemoteAddr()));
    }

    @PutMapping("/{draftId}")
    public Result<TravelRequirementRequests.Response> update(@PathVariable Long draftId,
                                                              @Valid @RequestBody TravelRequirementRequests.Update request,
                                                              HttpServletRequest http) {
        return Result.success(service.update(user(http), draftId, request));
    }

    @PostMapping("/{draftId}/confirm")
    public Result<TravelRequirementRequests.Response> confirm(@PathVariable Long draftId,
                                                               @Valid @RequestBody TravelRequirementRequests.Confirm request,
                                                               HttpServletRequest http) {
        return Result.success(service.confirm(user(http), JwtAuthInterceptor.getUserLevel(http), draftId, request, http.getRemoteAddr()));
    }

    private Long user(HttpServletRequest request) { return JwtAuthInterceptor.getUserId(request); }
}
