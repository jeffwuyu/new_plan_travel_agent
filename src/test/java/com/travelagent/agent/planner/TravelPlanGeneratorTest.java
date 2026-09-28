package com.travelagent.agent.planner;

import com.travelagent.agent.requirements.TravelConstraints;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("TravelPlanGenerator Tests")
class TravelPlanGeneratorTest {

    private final TravelPlanGenerator generator = new TravelPlanGenerator();

    @Test
    void generate_createsExecutablePlanWithRequiredSubtasksAndDependencyGraph() {
        TravelPlan plan = generator.generate(completeConstraints());

        assertThat(plan.getGoal()).isEqualTo("北京 3 day travel plan");
        assertThat(plan.getConstraints().getDestination()).isEqualTo("北京");
        assertThat(plan.getStatus()).isEqualTo(PlanTaskStatus.PENDING);
        assertThat(plan.getTasks()).hasSize(9);

        Map<PlanTaskType, TravelPlanTask> byType = plan.getTasks().stream()
                .collect(Collectors.toMap(TravelPlanTask::getTaskType, task -> task));

        assertThat(byType.keySet()).containsExactlyInAnyOrder(
                PlanTaskType.WEATHER_QUERY,
                PlanTaskType.MAP_QUERY,
                PlanTaskType.WEB_REALTIME_QUERY,
                PlanTaskType.RAG_RETRIEVAL,
                PlanTaskType.BOOKING_QUERY,
                PlanTaskType.ACCOMMODATION_ANALYSIS,
                PlanTaskType.BUDGET_ESTIMATION,
                PlanTaskType.ITINERARY_GENERATION,
                PlanTaskType.VALIDATOR_CHECK);
        assertThat(byType.get(PlanTaskType.WEATHER_QUERY).getToolType()).isEqualTo(PlannerToolType.WEATHER);
        assertThat(byType.get(PlanTaskType.MAP_QUERY).getToolType()).isEqualTo(PlannerToolType.AMAP);
        assertThat(byType.get(PlanTaskType.RAG_RETRIEVAL).getToolType()).isEqualTo(PlannerToolType.RAG);

        TravelPlanTask itinerary = byType.get(PlanTaskType.ITINERARY_GENERATION);
        assertThat(itinerary.getDependencies()).contains(
                byType.get(PlanTaskType.WEATHER_QUERY).getTaskId(),
                byType.get(PlanTaskType.MAP_QUERY).getTaskId(),
                byType.get(PlanTaskType.RAG_RETRIEVAL).getTaskId(),
                byType.get(PlanTaskType.BOOKING_QUERY).getTaskId());
        assertThat(plan.getDependencies()).containsEntry(itinerary.getTaskId(), itinerary.getDependencies());
        assertThat(itinerary.getSuccessCriteria()).contains("generates day-by-day itinerary");
    }

    @Test
    void runnableTasks_followDependencyStatusTransitions() {
        TravelPlan plan = generator.generate(completeConstraints());

        assertThat(plan.runnableTasks())
                .extracting(TravelPlanTask::getTaskType)
                .containsExactlyInAnyOrder(
                        PlanTaskType.WEATHER_QUERY,
                        PlanTaskType.MAP_QUERY,
                        PlanTaskType.WEB_REALTIME_QUERY,
                        PlanTaskType.RAG_RETRIEVAL);

        plan.findTask(PlanTaskType.WEATHER_QUERY).orElseThrow().markSuccess(Map.of("source", "amap"));
        plan.findTask(PlanTaskType.MAP_QUERY).orElseThrow().markSuccess(Map.of("routeCount", 3));
        plan.findTask(PlanTaskType.WEB_REALTIME_QUERY).orElseThrow().markSuccess(Map.of("sourceCount", 2));
        plan.findTask(PlanTaskType.RAG_RETRIEVAL).orElseThrow().markSuccess(Map.of("recallCount", 8));
        plan.findTask(PlanTaskType.BOOKING_QUERY).orElseThrow().markSuccess(Map.of("required", true));
        plan.findTask(PlanTaskType.ACCOMMODATION_ANALYSIS).orElseThrow().markSuccess(Map.of("area", "前门"));
        plan.findTask(PlanTaskType.BUDGET_ESTIMATION).orElseThrow().markSuccess(Map.of("total", 2800));

        assertThat(plan.runnableTasks())
                .extracting(TravelPlanTask::getTaskType)
                .containsExactly(PlanTaskType.ITINERARY_GENERATION);

        TravelPlanTask itinerary = plan.findTask(PlanTaskType.ITINERARY_GENERATION).orElseThrow();
        itinerary.markRunning();
        plan.refreshStatus();
        assertThat(plan.getStatus()).isEqualTo(PlanTaskStatus.RUNNING);

        itinerary.markFailed("route too intense");
        plan.refreshStatus();
        assertThat(itinerary.getStatus()).isEqualTo(PlanTaskStatus.FAILED);
        assertThat(itinerary.getRetryCount()).isEqualTo(1);
        assertThat(plan.getStatus()).isEqualTo(PlanTaskStatus.FAILED);
    }

    @Test
    void regenerate_incrementsVersionAndRecordsChangeReason() {
        TravelConstraints initial = completeConstraints();
        TravelPlan first = generator.generate(initial);

        TravelConstraints revised = completeConstraints();
        revised.setBudgetYuan(new BigDecimal("5000"));
        revised.setUpdatedFields(List.of("budgetYuan"));

        TravelPlan second = generator.regenerate(first, revised, "validator found budget pressure");

        assertThat(second.getPlanId()).isEqualTo(first.getPlanId());
        assertThat(second.getVersion()).isEqualTo(2);
        assertThat(second.getChangeReason()).isEqualTo("validator found budget pressure");
        assertThat(second.getChangeHistory()).hasSize(1);
        assertThat(second.getChangeHistory().get(0).getFromVersion()).isEqualTo(1);
        assertThat(second.getChangeHistory().get(0).getToVersion()).isEqualTo(2);
        assertThat(second.getChangeHistory().get(0).getChangedFields()).contains("budgetYuan");
        assertThat(second.findTask(PlanTaskType.BUDGET_ESTIMATION).orElseThrow().getInput())
                .containsEntry("budgetYuan", new BigDecimal("5000"));
    }

    @Test
    void generate_missingRequiredConstraintsFailsBeforePlanning() {
        TravelConstraints constraints = new TravelConstraints();
        constraints.setDestination("杭州");

        assertThatThrownBy(() -> generator.generate(constraints))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("required travel constraints are missing");
    }

    private TravelConstraints completeConstraints() {
        TravelConstraints constraints = new TravelConstraints();
        constraints.setRawText("两个人从上海去北京玩三天，预算3000元，喜欢历史景点和本地美食。");
        constraints.setDeparture("上海");
        constraints.setDestination("北京");
        constraints.setStartDate(LocalDate.of(2026, 7, 1));
        constraints.setEndDate(LocalDate.of(2026, 7, 3));
        constraints.setDays(3);
        constraints.setBudgetYuan(new BigDecimal("3000"));
        constraints.setPeopleCount(2);
        constraints.setTransportPreference(List.of("高铁"));
        constraints.setHotelPreference("交通方便");
        constraints.setAttractionPreference(List.of("历史"));
        constraints.setFoodPreference("本地美食");
        constraints.setTravelPace("relaxed");
        constraints.setBookingRequired(true);
        constraints.refreshMissingFields();
        return constraints;
    }
}
