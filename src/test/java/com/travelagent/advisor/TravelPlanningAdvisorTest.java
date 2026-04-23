package com.travelagent.advisor;

import com.travelagent.agent.context.CompletedStep;
import com.travelagent.agent.context.PlanningConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("TravelPlanningAdvisor Tests")
class TravelPlanningAdvisorTest {

    private final TravelPlanningAdvisor advisor = new TravelPlanningAdvisor();

    @Test
    @DisplayName("buildPlanningInstructions includes trip constraints and visited set")
    void buildPlanningInstructions_includesTripConstraints() {
        PlanningConfig config = new PlanningConfig();
        config.setTotalDays(3);
        config.setAttractionsPerDay(2);
        config.setTravelMode("driving");
        config.setPreferenceKeywords(List.of("history", "culture"));

        CompletedStep step = new CompletedStep();
        step.setAttractionName("Terracotta Army");

        String text = advisor.buildPlanningInstructions(Map.of(
                AdvisorContextKeys.REGION, "Xi'an",
                AdvisorContextKeys.USER_INTENT, "3 day history trip",
                AdvisorContextKeys.PLANNING_CONFIG, config,
                AdvisorContextKeys.COMPLETED_STEPS, List.of(step),
                AdvisorContextKeys.SAME_DAY_RADIUS_KM, 30
        ));

        assertThat(text).contains("Destination region: Xi'an");
        assertThat(text).contains("totalDays=3");
        assertThat(text).contains("history, culture");
        assertThat(text).contains("Terracotta Army");
        assertThat(text).contains("15km");
    }
}
