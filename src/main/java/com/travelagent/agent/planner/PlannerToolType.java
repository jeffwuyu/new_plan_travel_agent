package com.travelagent.agent.planner;

import lombok.Getter;

@Getter
public enum PlannerToolType {
    WEATHER("weather"),
    AMAP("amap"),
    WEB_SEARCH("web_search"),
    RAG("rag"),
    BOOKING_QUERY("booking_query"),
    HOTEL_ANALYSIS("hotel_analysis"),
    BUDGET("budget"),
    ITINERARY("itinerary"),
    VALIDATOR("validator");

    private final String code;

    PlannerToolType(String code) {
        this.code = code;
    }
}
