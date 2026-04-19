package com.travelagent.agent.context;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Immutable planning parameters captured at task creation time.
 * Stored inside TaskCheckpoint so the agent loop can re-read its own config
 * after a resume without hitting the database.
 */

/**
 * 中文注释：Agent 上下文类，用于承载 Planning Config 相关的运行时状态数据。
 */

@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class PlanningConfig {

    /** Total number of days for the trip (1–14). */
    private int totalDays = 3;

    /** Number of attractions to visit per day (1–6). */
    private int attractionsPerDay = 3;

    /** User-supplied preference tags, e.g. ["历史", "美食", "自然"]. */
    private List<String> preferenceKeywords;

    /** Travel mode: driving | walking | transit */
    private String travelMode = "driving";

    /** Convenience: total number of steps = totalDays * attractionsPerDay. */
    public int totalSteps() {
        return totalDays * attractionsPerDay;
    }
}
