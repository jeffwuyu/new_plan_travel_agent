package com.travelagent.agent.planner;

import com.travelagent.agent.context.CompletedStep;
import com.travelagent.agent.context.DailyTimeWindow;
import com.travelagent.agent.context.PlanningConfig;
import com.travelagent.agent.context.TaskCheckpoint;
import com.travelagent.model.dto.ResolvedLocation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("PlannerPromptBuilder Tests")
class PlannerPromptBuilderTest {

    private final PlannerPromptBuilder promptBuilder = new PlannerPromptBuilder();

    @Test
    void buildSystemPrompt_includesTimeBudgetAndDestinationConstraint() {
        TaskCheckpoint cp = buildCheckpoint(2, 3, "Xi'an", List.of(), List.of("history"));

        String prompt = promptBuilder.buildSystemPrompt(cp);

        assertThat(prompt).contains("Xi'an");
        assertThat(prompt).contains("Trip window");
        assertThat(prompt).contains("Remaining planning budget");
        assertThat(prompt).contains("Soft destination constraint");
    }

    @Test
    void buildStepPrompt_firstStep_mentionsStartAndRemainingBudget() {
        TaskCheckpoint cp = buildCheckpoint(2, 3, "Xi'an", List.of(), List.of());
        cp.setCurrentStepIndex(0);

        String prompt = promptBuilder.buildStepPrompt(cp);

        assertThat(prompt).contains("overall 1/6");
        assertThat(prompt).contains("Start from Bell Tower");
        assertThat(prompt).contains("Remaining total planning budget");
    }

    @Test
    void buildStepPrompt_laterStep_includesPreviousCoordinatesAndDestinationReserve() {
        CompletedStep prev = step("Wild Goose Pagoda", 34.22, 108.96);
        TaskCheckpoint cp = buildCheckpoint(1, 4, "Xi'an", List.of(prev), List.of());
        cp.setCurrentStepIndex(1);
        cp.setProjectedReturnToDestinationMin(38);

        String prompt = promptBuilder.buildStepPrompt(cp);

        assertThat(prompt).contains("Wild Goose Pagoda");
        assertThat(prompt).contains("34.22");
        assertThat(prompt).contains("38 minutes");
    }

    private TaskCheckpoint buildCheckpoint(int days, int perDay, String region,
                                           List<CompletedStep> steps,
                                           List<String> prefs) {
        TaskCheckpoint cp = new TaskCheckpoint();
        cp.setTaskUuid("test-uuid");
        cp.setTaskId(1L);
        cp.setRegion(region);
        cp.setUserIntent("Explore " + region);
        cp.setStartLocationQuery("Bell Tower");
        cp.setEndLocationQuery("Xi'an North Station");
        cp.setTripStartTime(LocalDateTime.of(2026, 4, 22, 9, 0));
        cp.setTripEndTime(LocalDateTime.of(2026, 4, 23, 18, 0));

        PlanningConfig config = new PlanningConfig();
        config.setTotalDays(days);
        config.setAttractionsPerDay(perDay);
        config.setDynamicTargetSteps(days * perDay);
        config.setTravelMode("driving");
        config.setPreferenceKeywords(prefs);
        config.setStartLocationQuery("Bell Tower");
        config.setEndLocationQuery("Xi'an North Station");
        config.setStartTime(cp.getTripStartTime());
        config.setEndTime(cp.getTripEndTime());
        config.setFullDayStartTime(LocalTime.of(7, 0));
        config.setFullDayEndTime(LocalTime.of(21, 0));
        cp.setPlanningConfig(config);

        cp.setDailyTimeWindows(List.of(
                new DailyTimeWindow(1, LocalDateTime.of(2026, 4, 22, 9, 0), LocalDateTime.of(2026, 4, 22, 21, 0)),
                new DailyTimeWindow(2, LocalDateTime.of(2026, 4, 23, 7, 0), LocalDateTime.of(2026, 4, 23, 18, 0))
        ));
        cp.setRemainingTimeBudgetMin(480);

        ResolvedLocation origin = new ResolvedLocation();
        origin.setName("Bell Tower");
        origin.setLatitude(34.26);
        origin.setLongitude(108.95);
        cp.setSelectedOrigin(origin);

        ResolvedLocation destination = new ResolvedLocation();
        destination.setName("Xi'an North Station");
        destination.setLatitude(34.38);
        destination.setLongitude(108.94);
        cp.setSelectedDestination(destination);

        cp.setCompletedSteps(new ArrayList<>(steps));
        cp.setLlmConversationHistory(new ArrayList<>());
        cp.setCurrentStepIndex(steps.size());
        cp.setSelectedBranchType("manual");
        return cp;
    }

    private CompletedStep step(String name, double lat, double lng) {
        CompletedStep s = new CompletedStep();
        s.setAttractionName(name);
        s.setLat(lat);
        s.setLng(lng);
        s.setDayNumber(1);
        return s;
    }
}
