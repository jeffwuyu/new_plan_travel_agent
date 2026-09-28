package com.travelagent.service.accommodation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.travelagent.agent.context.PlanningConfig;
import com.travelagent.agent.context.TaskCheckpoint;
import com.travelagent.mapper.PlanMapper;
import com.travelagent.mapper.TaskMapper;
import com.travelagent.model.entity.Plan;
import com.travelagent.model.entity.PlanAccommodation;
import com.travelagent.model.entity.Task;
import com.travelagent.util.JsonUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PlanAccommodationServiceTest {

    @Mock private PlanMapper planMapper;
    @Mock private TaskMapper taskMapper;
    @Mock private AccommodationRecommendationService recommendationService;

    private JsonUtil jsonUtil;
    private PlanAccommodationService service;

    @BeforeEach
    void setUp() {
        jsonUtil = new JsonUtil();
        ReflectionTestUtils.setField(jsonUtil, "objectMapper", new ObjectMapper().findAndRegisterModules());
        service = new PlanAccommodationService(planMapper, taskMapper, recommendationService, jsonUtil, 6);
    }

    @Test
    void markFreshness_flagsMissingAndOldPricesAsStale() {
        PlanAccommodation fresh = accommodation(LocalDateTime.of(2026, 6, 17, 10, 0), "fresh");
        PlanAccommodation stale = accommodation(LocalDateTime.of(2026, 6, 17, 3, 59), "stale");
        PlanAccommodation unknown = accommodation(null, "unknown");

        List<PlanAccommodation> marked = service.markFreshness(
                List.of(fresh, stale, unknown),
                LocalDateTime.of(2026, 6, 17, 10, 0));

        assertThat(marked).extracting(PlanAccommodation::getPriceStale)
                .containsExactly(false, true, true);
        assertThat(fresh.getPriceAgeHours()).isZero();
        assertThat(stale.getPriceAgeHours()).isEqualTo(6);
        assertThat(unknown.getPriceAgeHours()).isNull();
    }

    @Test
    void refreshAccommodations_replacesOldRowsAndReturnsFreshnessMetadata() {
        Plan plan = plan(100L, 1L, 10L);
        Task task = task(10L, checkpointJson());
        PlanAccommodation recommended = accommodation(LocalDateTime.now(), "new-hotel");
        Plan refreshed = plan(100L, 1L, 10L);

        when(planMapper.findById(100L)).thenReturn(plan, refreshed);
        when(taskMapper.findById(10L)).thenReturn(task);
        when(recommendationService.recommend(eq(100L), any(TaskCheckpoint.class)))
                .thenReturn(AccommodationRecommendationResult.available(List.of(recommended)));
        when(planMapper.findAccommodationsByPlanId(100L)).thenReturn(List.of(recommended));

        Plan result = service.refreshAccommodations(100L, 1L);

        verify(planMapper).updateAccommodationStatus(100L, "available", null);
        verify(planMapper).deleteAccommodationsByPlanId(100L);
        ArgumentCaptor<List<PlanAccommodation>> captor = ArgumentCaptor.forClass(List.class);
        verify(planMapper).insertAccommodations(captor.capture());
        assertThat(captor.getValue()).hasSize(1);
        assertThat(result.getAccommodations()).hasSize(1);
        assertThat(result.getAccommodations().get(0).getPriceStale()).isFalse();
    }

    @Test
    void refreshAccommodations_clearsOldRowsWhenProviderUnavailable() {
        Plan plan = plan(100L, 1L, 10L);
        Task task = task(10L, checkpointJson());
        Plan refreshed = plan(100L, 1L, 10L);

        when(planMapper.findById(100L)).thenReturn(plan, refreshed);
        when(taskMapper.findById(10L)).thenReturn(task);
        when(recommendationService.recommend(eq(100L), any(TaskCheckpoint.class)))
                .thenReturn(AccommodationRecommendationResult.unavailable("OTA returned no hotel"));
        when(planMapper.findAccommodationsByPlanId(100L)).thenReturn(List.of());

        Plan result = service.refreshAccommodations(100L, 1L);

        verify(planMapper).updateAccommodationStatus(100L, "unavailable", "OTA returned no hotel");
        verify(planMapper).deleteAccommodationsByPlanId(100L);
        verify(planMapper, never()).insertAccommodations(any());
        assertThat(result.getAccommodations()).isEmpty();
    }

    private Plan plan(Long id, Long userId, Long taskId) {
        Plan plan = new Plan();
        plan.setId(id);
        plan.setUserId(userId);
        plan.setTaskId(taskId);
        return plan;
    }

    private Task task(Long id, String checkpointJson) {
        Task task = new Task();
        task.setId(id);
        task.setCheckpointJson(checkpointJson);
        return task;
    }

    private String checkpointJson() {
        PlanningConfig config = new PlanningConfig();
        config.setTotalDays(2);
        config.setStartTime(LocalDateTime.of(2026, 7, 1, 10, 0));
        config.setLodgingBudgetPerNightYuan(new BigDecimal("500"));
        TaskCheckpoint checkpoint = new TaskCheckpoint();
        checkpoint.setPlanningConfig(config);
        return jsonUtil.toJson(checkpoint);
    }

    private PlanAccommodation accommodation(LocalDateTime fetchedAt, String hotelId) {
        PlanAccommodation accommodation = new PlanAccommodation();
        accommodation.setPlanId(100L);
        accommodation.setProviderHotelId(hotelId);
        accommodation.setPriceFetchedAt(fetchedAt);
        return accommodation;
    }
}
