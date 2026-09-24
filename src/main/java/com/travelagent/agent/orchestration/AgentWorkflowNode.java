package com.travelagent.agent.orchestration;

import com.travelagent.agent.planner.PlanTaskType;

import java.util.Optional;

public enum AgentWorkflowNode {
    INTENT_CONSTRAINT_PARSER(null),
    PLANNER(null),
    WEATHER_EXECUTION(PlanTaskType.WEATHER_QUERY),
    MAP_EXECUTION(PlanTaskType.MAP_QUERY),
    WEB_SEARCH_EXECUTION(PlanTaskType.WEB_REALTIME_QUERY),
    RAG_EXECUTION(PlanTaskType.RAG_RETRIEVAL),
    BOOKING_QUERY_EXECUTION(PlanTaskType.BOOKING_QUERY),
    HOTEL_ANALYSIS_EXECUTION(PlanTaskType.ACCOMMODATION_ANALYSIS),
    BUDGET_SCORING_EXECUTION(PlanTaskType.BUDGET_ESTIMATION),
    FINAL_ITINERARY_GENERATOR(PlanTaskType.ITINERARY_GENERATION),
    OBSERVE_RESULTS(null),
    VALIDATOR(PlanTaskType.VALIDATOR_CHECK),
    REPLAN(null),
    COMPLETE(null),
    FAILED(null);

    private final PlanTaskType planTaskType;

    AgentWorkflowNode(PlanTaskType planTaskType) {
        this.planTaskType = planTaskType;
    }

    public Optional<PlanTaskType> planTaskType() {
        return Optional.ofNullable(planTaskType);
    }
}
