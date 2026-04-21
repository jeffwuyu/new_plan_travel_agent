package com.travelagent.agent.planner;

import java.util.List;

/**
 * Parsed result from the final LLM summary call made after all planning steps complete.
 *
 * <p>Contains the plan title, a human-readable trip summary, and per-step descriptions
 * with LLM-estimated visit durations.
 *
 * <p>All fields fall back to safe defaults when the LLM response cannot be parsed,
 * ensuring {@code persistPlan()} never fails due to a bad LLM response.
 *
 * @param title    short plan title (e.g. "西安 3 日历史文化游")
 * @param summary  one-paragraph trip overview
 * @param steps    per-step description + duration estimate, indexed by {@code stepOrder}
 */
public record FinalSummaryResult(
        String title,
        String summary,
        List<StepSummary> steps
) {

    /**
     * Per-step data returned by the final summary call.
     *
     * @param stepOrder            0-based index matching {@code CompletedStep.stepIndex}
     * @param estimatedDurationMin recommended visit time in minutes (replaces the hardcoded 90)
     * @param llmDescription       short visit note for display (e.g. "建议上午游览，预留3小时")
     */
    public record StepSummary(
            int stepOrder,
            int estimatedDurationMin,
            String llmDescription
    ) {}
}
