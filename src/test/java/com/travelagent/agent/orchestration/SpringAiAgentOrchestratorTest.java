package com.travelagent.agent.orchestration;

import com.travelagent.agent.planner.PlanTaskStatus;
import com.travelagent.agent.planner.PlanTaskType;
import com.travelagent.agent.planner.TravelPlanGenerator;
import com.travelagent.agent.requirements.TravelRequirementParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Spring AI Agent Orchestrator Tests")
class SpringAiAgentOrchestratorTest {

    private SpringAiAgentOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        orchestrator = new SpringAiAgentOrchestrator(
                new TravelRequirementParser(),
                new TravelPlanGenerator());
    }

    @Test
    void execute_completeRequestRunsFullPlanningLoop() {
        AgentWorkflowContext context = orchestrator.execute(completeRequestText());

        assertThat(context.isComplete()).isTrue();
        assertThat(context.getPlan().getStatus()).isEqualTo(PlanTaskStatus.SUCCESS);
        assertThat(context.getFinalItinerary()).contains("北京", "3 days");
        assertThat(nodes(context)).containsSequence(
                AgentWorkflowNode.INTENT_CONSTRAINT_PARSER,
                AgentWorkflowNode.PLANNER,
                AgentWorkflowNode.WEATHER_EXECUTION,
                AgentWorkflowNode.MAP_EXECUTION,
                AgentWorkflowNode.WEB_SEARCH_EXECUTION,
                AgentWorkflowNode.RAG_EXECUTION,
                AgentWorkflowNode.BOOKING_QUERY_EXECUTION,
                AgentWorkflowNode.HOTEL_ANALYSIS_EXECUTION,
                AgentWorkflowNode.BUDGET_SCORING_EXECUTION,
                AgentWorkflowNode.FINAL_ITINERARY_GENERATOR,
                AgentWorkflowNode.OBSERVE_RESULTS,
                AgentWorkflowNode.VALIDATOR);
        assertThat(context.getTraces())
                .allSatisfy(trace -> {
                    assertThat(trace.getInput()).isNotNull();
                    assertThat(trace.getOutput()).isNotNull();
                });
    }

    @Test
    void execute_toolFailureRetriesAndKeepsNodeTrace() {
        AgentWorkflowContext context = new AgentWorkflowContext(completeRequestText());
        context.getSimulatedFailuresRemaining().put(PlanTaskType.WEATHER_QUERY, 1);

        orchestrator.execute(context);

        assertThat(context.isComplete()).isTrue();
        assertThat(context.getPlan().findTask(PlanTaskType.WEATHER_QUERY)).get()
                .satisfies(task -> {
                    assertThat(task.getStatus()).isEqualTo(PlanTaskStatus.SUCCESS);
                    assertThat(task.getRetryCount()).isEqualTo(1);
                });
        assertThat(context.getTraces().stream()
                .filter(trace -> trace.getNode() == AgentWorkflowNode.WEATHER_EXECUTION)
                .toList())
                .extracting(AgentNodeTrace::getRoute)
                .containsExactly(AgentWorkflowRoute.RETRY, AgentWorkflowRoute.NEXT);
    }

    @Test
    void execute_validatorFailureRoutesThroughReplan() {
        AgentWorkflowContext context = new AgentWorkflowContext(completeRequestText());
        context.setValidatorFailuresRemaining(1);

        orchestrator.execute(context);

        assertThat(context.isComplete()).isTrue();
        assertThat(context.getPlan().getVersion()).isEqualTo(2);
        assertThat(context.getPlan().getChangeHistory()).hasSize(1);
        assertThat(nodes(context)).contains(AgentWorkflowNode.REPLAN);
        assertThat(context.getTraces().stream()
                .filter(trace -> trace.getNode() == AgentWorkflowNode.FINAL_ITINERARY_GENERATOR)
                .count()).isEqualTo(2);
        assertThat(context.getPlan().getStatus()).isEqualTo(PlanTaskStatus.SUCCESS);
    }

    private List<AgentWorkflowNode> nodes(AgentWorkflowContext context) {
        return context.getTraces().stream()
                .map(AgentNodeTrace::getNode)
                .toList();
    }

    private String completeRequestText() {
        return "两个人从上海去北京玩三天，2026年7月1日出发，预算3000元，喜欢历史景点和本地美食，需要预约。";
    }
}
