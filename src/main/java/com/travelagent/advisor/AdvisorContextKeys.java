package com.travelagent.advisor;

/**
 * Shared advisor-context keys for travel-planning chat requests.
 */
public final class AdvisorContextKeys {

    public static final String REGION = "region";
    public static final String USER_INTENT = "userIntent";
    public static final String PLANNING_CONFIG = "planningConfig";
    public static final String COMPLETED_STEPS = "completedSteps";
    public static final String RAG_CHUNKS = "ragChunks";
    public static final String RESPONSE_SCHEMA = "responseSchema";
    public static final String SAME_DAY_RADIUS_KM = "sameDayRadiusKm";
    public static final String CURRENT_DAY_NUMBER = "currentDayNumber";

    private AdvisorContextKeys() {
    }
}
