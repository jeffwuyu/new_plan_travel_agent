package com.travelagent.agent.tools;

import java.util.Map;

/**
 * Common interface for all agent tools used in the Markov planning loop.
 *
 * <p>Each implementation is a Spring-managed singleton registered in
 * {@link ToolRegistry} at startup. Tool names must be unique and match the
 * {@code toolName} stored in {@code TaskCheckpoint.PendingToolCall}.
 */

/**
 * 中文注释：Agent 工具接口，负责执行 Agent Tool 相关的工具调用能力。
 */

public interface AgentTool {

    /**
     * Returns the unique tool identifier used in the registry and checkpoint.
     * Must be a stable, lowercase_underscore string (e.g. {@code "geocode"}).
     */
    String getName();

    /**
     * Execute the tool.
     *
     * @param arguments      raw key-value inputs (from LLM decision or checkpoint replay)
     * @param idempotencyKey unique key for this invocation; the {@link com.travelagent.aop.IdempotencyAspect}
     *                       uses this to deduplicate calls after a task resume.
     *                       Format: {@code {taskUuid}-step{N}-{toolName}}
     * @return result map stored in {@code CompletedStep.toolCallResults}
     */
    Map<String, Object> execute(Map<String, Object> arguments, String idempotencyKey);
}
