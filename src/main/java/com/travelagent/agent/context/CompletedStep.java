package com.travelagent.agent.context;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * A fully-resolved planning step that has been committed to the checkpoint.
 * Tool call results are stored as raw maps so that Phase 3 tool schemas can
 * evolve without requiring a checkpoint migration.
 */

/**
 * 中文注释：Agent 上下文类，用于承载 Completed Step 相关的运行时状态数据。
 */

@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class CompletedStep {

    /** 0-based global step index across all days. */
    private int stepIndex;

    /** 1-based day number (1 = first day). */
    private int dayNumber;

    private String attractionName;

    private Double lat;

    private Double lng;

    /**
     * Raw tool call results keyed by tool name, e.g.:
     *   "geocode"  → { "lat": 34.38, "lng": 109.28, ... }
     *   "weather"  → { "condition": "晴", "tempC": 22, ... }
     *   "traffic"  → { "durationMin": 25, ... }
     * Using Object values keeps this schema-free — Phase 3 deserializes per tool.
     */
    private Map<String, Object> toolCallResults;
}
