package com.travelagent.service.accommodation;

import com.travelagent.agent.context.TaskCheckpoint;
import com.travelagent.exception.BusinessException;
import com.travelagent.mapper.PlanMapper;
import com.travelagent.mapper.TaskMapper;
import com.travelagent.model.entity.Plan;
import com.travelagent.model.entity.PlanAccommodation;
import com.travelagent.model.entity.Task;
import com.travelagent.util.JsonUtil;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

@Service
public class PlanAccommodationService {

    private final PlanMapper planMapper;
    private final TaskMapper taskMapper;
    private final AccommodationRecommendationService recommendationService;
    private final JsonUtil jsonUtil;
    private final long freshnessTtlHours;

    public PlanAccommodationService(PlanMapper planMapper,
                                    TaskMapper taskMapper,
                                    AccommodationRecommendationService recommendationService,
                                    JsonUtil jsonUtil,
                                    @Value("${accommodation.price-freshness-ttl-hours:6}") long freshnessTtlHours) {
        this.planMapper = planMapper;
        this.taskMapper = taskMapper;
        this.recommendationService = recommendationService;
        this.jsonUtil = jsonUtil;
        this.freshnessTtlHours = Math.max(1L, freshnessTtlHours);
    }

    public List<PlanAccommodation> loadForPlan(Long planId) {
        return markFreshness(planMapper.findAccommodationsByPlanId(planId), LocalDateTime.now());
    }

    public List<PlanAccommodation> markFreshness(List<PlanAccommodation> accommodations, LocalDateTime now) {
        if (accommodations == null || accommodations.isEmpty()) {
            return accommodations == null ? List.of() : accommodations;
        }
        for (PlanAccommodation accommodation : accommodations) {
            LocalDateTime fetchedAt = accommodation.getPriceFetchedAt();
            if (fetchedAt == null) {
                accommodation.setPriceAgeHours(null);
                accommodation.setPriceStale(true);
                continue;
            }
            long ageHours = Math.max(0L, Duration.between(fetchedAt, now).toHours());
            accommodation.setPriceAgeHours(ageHours > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) ageHours);
            accommodation.setPriceStale(ageHours >= freshnessTtlHours);
        }
        return accommodations;
    }

    @Transactional
    public Plan refreshAccommodations(Long planId, Long userId) {
        Plan plan = planMapper.findById(planId);
        if (plan == null) {
            throw new BusinessException(404, "plan not found");
        }
        if (!plan.getUserId().equals(userId)) {
            throw new BusinessException(403, "no permission to access this plan");
        }
        Task task = taskMapper.findById(plan.getTaskId());
        if (task == null) {
            throw new BusinessException(404, "task not found for plan");
        }
        if (task.getCheckpointJson() == null || task.getCheckpointJson().isBlank()) {
            throw new BusinessException(409, "task checkpoint is missing; accommodation cannot be refreshed");
        }

        TaskCheckpoint checkpoint = parseCheckpoint(task);
        AccommodationRecommendationResult result = recommendationService.recommend(planId, checkpoint);
        planMapper.updateAccommodationStatus(planId, result.status(), result.failureReason());
        planMapper.deleteAccommodationsByPlanId(planId);
        if (!result.accommodations().isEmpty()) {
            planMapper.insertAccommodations(result.accommodations());
        }

        Plan refreshed = planMapper.findById(planId);
        if (refreshed == null) {
            throw new BusinessException(404, "plan not found after accommodation refresh");
        }
        refreshed.setAccommodations(loadForPlan(planId));
        return refreshed;
    }

    private TaskCheckpoint parseCheckpoint(Task task) {
        try {
            return jsonUtil.fromJson(task.getCheckpointJson(), TaskCheckpoint.class);
        } catch (IllegalStateException e) {
            throw new BusinessException(409, "task checkpoint is invalid; accommodation cannot be refreshed");
        }
    }
}
