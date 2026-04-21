package com.travelagent.agent.planner;

/**
 * Result of a single Markov planning step: the selected attraction name and the
 * token count consumed by the LLM call (0 when streaming mode returns no usage data).
 */
public record PlanningResult(String attractionName, int totalTokens) {}
