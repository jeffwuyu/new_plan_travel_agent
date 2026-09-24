package com.travelagent.agent.planner;

import lombok.Getter;

@Getter
public enum PlanTaskType {
    WEATHER_QUERY("weather_query"),
    MAP_QUERY("map_query"),
    WEB_REALTIME_QUERY("web_realtime_query"),
    RAG_RETRIEVAL("rag_retrieval"),
    BOOKING_QUERY("booking_query"),
    ACCOMMODATION_ANALYSIS("accommodation_analysis"),
    BUDGET_ESTIMATION("budget_estimation"),
    ITINERARY_GENERATION("itinerary_generation"),
    VALIDATOR_CHECK("validator_check");

    private final String code;

    PlanTaskType(String code) {
        this.code = code;
    }
}
