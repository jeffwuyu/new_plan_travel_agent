package com.travelagent.service.agent;

/**
 * Core agent execution service.
 *
 * <p>Called by {@link com.travelagent.agent.planner.TaskDispatcher} on a background
 * thread from {@code agentTaskExecutor}. Implementations are expected to be
 * long-running and should update task state and push SSE events during execution.
 *
 * <p><b>Phase 2:</b> Stub implementation only — transitions task to PLANNING.
 * <br><b>Phase 3-4:</b> Full Markov planning loop with tool calls, history
 * compression, quota enforcement, and checkpoint persistence.
 */

/**
 * 中文注释：服务接口，定义 Agent Service 相关业务能力。
 */

public interface AgentService {

    /**
     * Execute (or resume) the agent loop for the given task.
     *
     * <p>This method is expected to:
     * <ol>
     *   <li>Transition the task from PENDING/RESUMING → PLANNING.</li>
     *   <li>Iterate through the planning loop (LLM + tool calls).</li>
     *   <li>Persist checkpoint after each step.</li>
     *   <li>Transition to COMPLETED, PAUSED, or FAILED on exit.</li>
     * </ol>
     *
     * <p>Exceptions thrown by this method are caught by the dispatcher — the task
     * is left in an indeterminate state, so implementations must ensure a final
     * status update even on unexpected errors.
     *
     * @param taskUuid externally-visible UUID of the task to execute
     */
    void executeTask(String taskUuid);
}
