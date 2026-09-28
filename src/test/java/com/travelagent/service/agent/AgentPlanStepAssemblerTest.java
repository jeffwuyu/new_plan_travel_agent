package com.travelagent.service.agent;

import com.travelagent.agent.context.CompletedStep;
import com.travelagent.agent.planner.FinalSummaryResult;
import com.travelagent.agent.tools.TrafficTimeTool;
import com.travelagent.agent.tools.WeatherTool;
import com.travelagent.model.entity.PlanStep;
import com.travelagent.service.agent.impl.AgentPlanStepAssembler;
import com.travelagent.util.JsonUtil;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AgentPlanStepAssemblerTest {

    @Test
    void invalidToolResultsAreNotAppliedToPlanStep() {
        CompletedStep completedStep = new CompletedStep();
        completedStep.setStepIndex(0);
        completedStep.setDayNumber(1);
        completedStep.setAttractionName("Stop");
        completedStep.setTrafficTimeFromPrevMin(12);
        completedStep.setEstimatedVisitDurationMin(30);
        completedStep.setToolCallResults(Map.of(
                TrafficTimeTool.NAME, Map.of(
                        "durationMin", 88,
                        "available", false,
                        "resultValidation", Map.of("valid", false)
                ),
                WeatherTool.NAME, Map.of(
                        "weather", "sunny",
                        "temperature", "26",
                        "resultValidation", Map.of("valid", false)
                )
        ));

        AgentPlanStepAssembler assembler = new AgentPlanStepAssembler(new JsonUtil());
        List<PlanStep> steps = assembler.buildPlanSteps(1L, List.of(completedStep), List.<FinalSummaryResult.StepSummary>of());

        assertThat(steps).hasSize(1);
        assertThat(steps.get(0).getTrafficTimeFromPrev()).isEqualTo(12);
        assertThat(steps.get(0).getWeatherNote()).isNull();
        assertThat(steps.get(0).getSelectedRouteGeometryJson()).isNull();
    }
}
