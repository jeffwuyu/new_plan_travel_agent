package com.travelagent.agent.orchestration;

import com.travelagent.agent.tools.AmapMapTool;
import com.travelagent.agent.tools.BookingQueryTool;
import com.travelagent.agent.tools.RagTool;
import com.travelagent.agent.tools.ToolCallRequest;
import com.travelagent.agent.tools.ToolCallResult;
import com.travelagent.agent.tools.ToolCallStatus;
import com.travelagent.agent.tools.WeatherTool;
import com.travelagent.agent.tools.WebSearchTool;
import com.travelagent.agent.planner.PlanTaskStatus;
import com.travelagent.agent.planner.PlanTaskType;
import com.travelagent.agent.planner.TravelPlan;
import com.travelagent.agent.planner.TravelPlanGenerator;
import com.travelagent.agent.planner.TravelPlanTask;
import com.travelagent.agent.requirements.TravelConstraints;
import com.travelagent.agent.requirements.TravelRequirementParser;
import com.travelagent.service.agent.impl.AgentCheckpointHelper;
import com.travelagent.service.agent.impl.AgentToolExecutor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class SpringAiAgentOrchestrator {

    private static final int MAX_NODE_STEPS = 128;

    private final TravelRequirementParser requirementParser;
    private final TravelPlanGenerator planGenerator;

    @Autowired(required = false)
    private AgentToolExecutor agentToolExecutor;

    @Autowired(required = false)
    private AgentCheckpointHelper checkpointHelper;

    public SpringAiAgentOrchestrator(TravelRequirementParser requirementParser,
                                     TravelPlanGenerator planGenerator) {
        this.requirementParser = requirementParser;
        this.planGenerator = planGenerator;
    }

    public AgentWorkflowContext execute(String requestText) {
        return execute(new AgentWorkflowContext(requestText));
    }

    public AgentWorkflowContext execute(AgentWorkflowContext context) {
        if (context == null) {
            throw new IllegalArgumentException("context is required");
        }
        if (context.getCurrentNode() == null) {
            context.setCurrentNode(AgentWorkflowNode.INTENT_CONSTRAINT_PARSER);
        }

        int steps = 0;
        while (!context.isComplete() && !context.isFailed()) {
            if (++steps > MAX_NODE_STEPS) {
                context.setCurrentNode(AgentWorkflowNode.FAILED);
                break;
            }
            AgentWorkflowNode node = context.getCurrentNode();
            Map<String, Object> input = inputFor(node, context);
            AgentNodeResult result = executeNode(node, context);
            context.record(node, result.getRoute(), input, result.getOutput(), result.getMessage());
            context.setCurrentNode(nextNode(node, result.getRoute(), context));
        }
        return context;
    }

    private AgentNodeResult executeNode(AgentWorkflowNode node, AgentWorkflowContext context) {
        return switch (node) {
            case INTENT_CONSTRAINT_PARSER -> parseIntentAndConstraints(context);
            case PLANNER -> buildPlan(context);
            case WEATHER_EXECUTION,
                    MAP_EXECUTION,
                    WEB_SEARCH_EXECUTION,
                    RAG_EXECUTION,
                    BOOKING_QUERY_EXECUTION,
                    HOTEL_ANALYSIS_EXECUTION,
                    BUDGET_SCORING_EXECUTION,
                    FINAL_ITINERARY_GENERATOR -> executePlanTask(node, context);
            case OBSERVE_RESULTS -> observeResults(context);
            case VALIDATOR -> validatePlan(context);
            case REPLAN -> replan(context);
            case COMPLETE -> AgentNodeResult.next(Map.of("status", "complete"));
            case FAILED -> AgentNodeResult.route(AgentWorkflowRoute.FAIL, Map.of("status", "failed"), "workflow failed");
        };
    }

    private AgentNodeResult parseIntentAndConstraints(AgentWorkflowContext context) {
        TravelConstraints constraints = requirementParser.parse(context.getRequestText());
        context.setConstraints(constraints);
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("constraints", constraints);
        output.put("missingFields", constraints.getMissingFields());
        output.put("mustAsk", constraints.isMustAsk());
        if (constraints.isMustAsk()) {
            return AgentNodeResult.route(AgentWorkflowRoute.FAIL, output, "required constraints are missing");
        }
        return AgentNodeResult.next(output);
    }

    private AgentNodeResult buildPlan(AgentWorkflowContext context) {
        TravelPlan plan = context.getPlan();
        if (plan == null) {
            plan = planGenerator.generate(context.getConstraints());
            context.setPlan(plan);
        }
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("planId", plan.getPlanId());
        output.put("version", plan.getVersion());
        output.put("goal", plan.getGoal());
        output.put("taskCount", plan.getTasks().size());
        output.put("dependencies", plan.getDependencies());
        return AgentNodeResult.next(output);
    }

    private AgentNodeResult executePlanTask(AgentWorkflowNode node, AgentWorkflowContext context) {
        PlanTaskType taskType = node.planTaskType()
                .orElseThrow(() -> new IllegalStateException("node has no plan task type: " + node));
        TravelPlanTask task = context.getPlan().findTask(taskType)
                .orElseThrow(() -> new IllegalStateException("plan task not found: " + taskType));
        task.markRunning();

        Integer remainingFailures = context.getSimulatedFailuresRemaining().getOrDefault(taskType, 0);
        if (remainingFailures > 0) {
            context.getSimulatedFailuresRemaining().put(taskType, remainingFailures - 1);
            task.markFailed("simulated " + taskType.getCode() + " failure");
            context.getPlan().refreshStatus();
            Map<String, Object> output = taskOutput(task, node);
            output.put("failed", true);
            if (task.getRetryCount() <= context.getMaxTaskRetries()) {
                return AgentNodeResult.route(AgentWorkflowRoute.RETRY, output, "retrying failed node");
            }
            task.markSkipped("degraded after retry budget exhausted");
            context.getPlan().refreshStatus();
            output.put("degraded", true);
            output.put("status", task.getStatus().getCode());
            return AgentNodeResult.route(AgentWorkflowRoute.DEGRADE, output, "degraded failed node");
        }

        if (shouldExecuteRealTool(node, context)) {
            return executeRealToolTask(node, context, task);
        }

        Map<String, Object> output = taskOutput(task, node);
        if (node == AgentWorkflowNode.FINAL_ITINERARY_GENERATOR) {
            String itinerary = buildDraftItinerary(context);
            context.setDraftItinerary(itinerary);
            output.put("draftItinerary", itinerary);
            output.put("usesObservedNodes", List.copyOf(context.getNodeOutputs().keySet()));
        }
        output.put("status", PlanTaskStatus.SUCCESS.getCode());
        task.markSuccess(output);
        context.getPlan().refreshStatus();
        return AgentNodeResult.next(output);
    }

    private boolean shouldExecuteRealTool(AgentWorkflowNode node, AgentWorkflowContext context) {
        return context.isRealToolExecutionEnabled()
                && agentToolExecutor != null
                && context.getTask() != null
                && context.getCheckpoint() != null
                && toolNameFor(node) != null;
    }

    private AgentNodeResult executeRealToolTask(AgentWorkflowNode node,
                                                AgentWorkflowContext context,
                                                TravelPlanTask task) {
        String toolName = toolNameFor(node);
        ToolCallRequest request = ToolCallRequest.of(toolName, toolArgumentsFor(task, context), null);
        request.setMaxRetries(context.getMaxTaskRetries());
        request.setDegradeOnFailure(true);
        ToolCallResult result = agentToolExecutor.runStructuredToolWithCheckpoint(
                context.getTask(),
                context.getCheckpoint(),
                request,
                context.getTask().getTaskUuid(),
                taskIndex(context, task));

        Map<String, Object> output = result.toMap();
        output.put("node", node.name());
        output.put("taskId", task.getTaskId());
        output.put("taskType", task.getTaskType().getCode());
        output.put("toolType", task.getToolType().getCode());

        if (result.getStatus() == ToolCallStatus.FAILED) {
            task.markFailed(result.getErrorMessage());
            context.getPlan().refreshStatus();
            savePlanState(context);
            return AgentNodeResult.route(AgentWorkflowRoute.FAIL, output, result.getErrorMessage());
        }
        if (result.getStatus() == ToolCallStatus.DEGRADED) {
            task.markSkipped(result.getDegradationReason());
            context.getPlan().refreshStatus();
            savePlanState(context);
            return AgentNodeResult.route(AgentWorkflowRoute.DEGRADE, output, result.getDegradationReason());
        }
        task.markSuccess(output);
        context.getPlan().refreshStatus();
        savePlanState(context);
        return AgentNodeResult.next(output);
    }

    private AgentNodeResult observeResults(AgentWorkflowContext context) {
        Map<String, Object> taskStatuses = new LinkedHashMap<>();
        int skipped = 0;
        int failed = 0;
        for (TravelPlanTask task : context.getPlan().getTasks()) {
            taskStatuses.put(task.getTaskType().getCode(), task.getStatus().getCode());
            if (task.getStatus() == PlanTaskStatus.SKIPPED) {
                skipped++;
            }
            if (task.getStatus() == PlanTaskStatus.FAILED) {
                failed++;
            }
        }
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("taskStatuses", taskStatuses);
        output.put("skippedCount", skipped);
        output.put("failedCount", failed);
        output.put("observedAt", Instant.now().toString());
        return AgentNodeResult.next(output);
    }

    private AgentNodeResult validatePlan(AgentWorkflowContext context) {
        TravelPlanTask validatorTask = context.getPlan().findTask(PlanTaskType.VALIDATOR_CHECK)
                .orElseThrow(() -> new IllegalStateException("validator task not found"));
        validatorTask.markRunning();

        Map<String, Object> output = new LinkedHashMap<>();
        output.put("checkedItinerary", context.getFinalItinerary());
        output.put("checkedDraftItinerary", context.getDraftItinerary());
        output.put("checkedAt", Instant.now().toString());
        if (context.getValidatorFailuresRemaining() > 0) {
            context.setValidatorFailuresRemaining(context.getValidatorFailuresRemaining() - 1);
            validatorTask.markFailed("validator issue requires re-plan");
            context.getPlan().refreshStatus();
            output.put("valid", false);
            output.put("issues", List.of("route intensity or budget needs adjustment"));
            return AgentNodeResult.route(AgentWorkflowRoute.REPLAN, output, "validator requested re-plan");
        }

        output.put("valid", true);
        output.put("issues", List.of());
        context.setFinalItinerary(buildFinalItinerary(context));
        output.put("finalItinerary", context.getFinalItinerary());
        validatorTask.markSuccess(output);
        context.getPlan().refreshStatus();
        return AgentNodeResult.next(output);
    }

    private AgentNodeResult replan(AgentWorkflowContext context) {
        TravelPlan previous = context.getPlan();
        TravelPlan next = planGenerator.regenerate(previous, context.getConstraints(),
                "validator issue requires route adjustment");
        context.setPlan(next);
        context.setDraftItinerary(null);
        context.setFinalItinerary(null);

        Map<String, Object> output = new LinkedHashMap<>();
        output.put("planId", next.getPlanId());
        output.put("fromVersion", previous.getVersion());
        output.put("toVersion", next.getVersion());
        output.put("changeReason", next.getChangeReason());
        output.put("changedFields", context.getConstraints().getUpdatedFields());
        return AgentNodeResult.next(output);
    }

    private AgentWorkflowNode nextNode(AgentWorkflowNode node,
                                       AgentWorkflowRoute route,
                                       AgentWorkflowContext context) {
        if (route == AgentWorkflowRoute.FAIL) {
            return AgentWorkflowNode.FAILED;
        }
        if (route == AgentWorkflowRoute.RETRY) {
            return node;
        }
        if (route == AgentWorkflowRoute.REPLAN) {
            return AgentWorkflowNode.REPLAN;
        }
        return switch (node) {
            case INTENT_CONSTRAINT_PARSER -> AgentWorkflowNode.PLANNER;
            case PLANNER, REPLAN -> nextExecutableNode(context);
            case WEATHER_EXECUTION,
                    MAP_EXECUTION,
                    WEB_SEARCH_EXECUTION,
                    RAG_EXECUTION,
                    BOOKING_QUERY_EXECUTION,
                    HOTEL_ANALYSIS_EXECUTION,
                    BUDGET_SCORING_EXECUTION,
                    FINAL_ITINERARY_GENERATOR -> nextExecutableNode(context);
            case OBSERVE_RESULTS -> AgentWorkflowNode.VALIDATOR;
            case VALIDATOR -> AgentWorkflowNode.COMPLETE;
            case COMPLETE -> AgentWorkflowNode.COMPLETE;
            case FAILED -> AgentWorkflowNode.FAILED;
        };
    }

    private AgentWorkflowNode nextExecutableNode(AgentWorkflowContext context) {
        return context.getPlan().runnableTasks().stream()
                .filter(task -> task.getTaskType() != PlanTaskType.VALIDATOR_CHECK)
                .findFirst()
                .map(task -> nodeFor(task.getTaskType()))
                .orElse(AgentWorkflowNode.OBSERVE_RESULTS);
    }

    private AgentWorkflowNode nodeFor(PlanTaskType taskType) {
        return switch (taskType) {
            case WEATHER_QUERY -> AgentWorkflowNode.WEATHER_EXECUTION;
            case MAP_QUERY -> AgentWorkflowNode.MAP_EXECUTION;
            case WEB_REALTIME_QUERY -> AgentWorkflowNode.WEB_SEARCH_EXECUTION;
            case RAG_RETRIEVAL -> AgentWorkflowNode.RAG_EXECUTION;
            case BOOKING_QUERY -> AgentWorkflowNode.BOOKING_QUERY_EXECUTION;
            case ACCOMMODATION_ANALYSIS -> AgentWorkflowNode.HOTEL_ANALYSIS_EXECUTION;
            case BUDGET_ESTIMATION -> AgentWorkflowNode.BUDGET_SCORING_EXECUTION;
            case ITINERARY_GENERATION -> AgentWorkflowNode.FINAL_ITINERARY_GENERATOR;
            case VALIDATOR_CHECK -> AgentWorkflowNode.VALIDATOR;
        };
    }

    private Map<String, Object> inputFor(AgentWorkflowNode node, AgentWorkflowContext context) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("requestText", context.getRequestText());
        if (context.getConstraints() != null) {
            input.put("constraints", context.getConstraints());
        }
        if (context.getPlan() != null) {
            input.put("planId", context.getPlan().getPlanId());
            input.put("planVersion", context.getPlan().getVersion());
        }
        node.planTaskType()
                .flatMap(taskType -> context.getPlan() == null ? java.util.Optional.<TravelPlanTask>empty() : context.getPlan().findTask(taskType))
                .ifPresent(task -> {
                    input.put("taskId", task.getTaskId());
                    input.put("taskType", task.getTaskType().getCode());
                    input.put("taskInput", task.getInput());
                    input.put("dependencies", task.getDependencies());
                });
        return input;
    }

    private Map<String, Object> taskOutput(TravelPlanTask task, AgentWorkflowNode node) {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("node", node.name());
        output.put("taskId", task.getTaskId());
        output.put("taskType", task.getTaskType().getCode());
        output.put("toolType", task.getToolType().getCode());
        output.put("status", task.getStatus().getCode());
        output.put("queryTime", Instant.now().toString());
        output.put("source", "orchestration:" + task.getToolType().getCode());
        output.put("retryCount", task.getRetryCount());
        output.put("inputEcho", task.getInput());
        return output;
    }

    private String toolNameFor(AgentWorkflowNode node) {
        return node.planTaskType()
                .map(taskType -> switch (taskType) {
                    case WEATHER_QUERY -> WeatherTool.NAME;
                    case MAP_QUERY -> AmapMapTool.NAME;
                    case WEB_REALTIME_QUERY -> WebSearchTool.NAME;
                    case RAG_RETRIEVAL -> RagTool.NAME;
                    case BOOKING_QUERY -> BookingQueryTool.NAME;
                    case ACCOMMODATION_ANALYSIS, BUDGET_ESTIMATION, ITINERARY_GENERATION, VALIDATOR_CHECK -> null;
                })
                .orElse(null);
    }

    private Map<String, Object> toolArgumentsFor(TravelPlanTask task, AgentWorkflowContext context) {
        Map<String, Object> arguments = new LinkedHashMap<>(task.getInput());
        TravelConstraints constraints = context.getConstraints();
        if (constraints != null) {
            arguments.putIfAbsent("city", constraints.getDestination());
            arguments.putIfAbsent("destination", constraints.getDestination());
            arguments.putIfAbsent("region", constraints.getDestination());
        }
        return arguments;
    }

    private int taskIndex(AgentWorkflowContext context, TravelPlanTask task) {
        int index = context.getPlan().getTasks().indexOf(task);
        return Math.max(0, index);
    }

    private void savePlanState(AgentWorkflowContext context) {
        if (checkpointHelper == null || context.getTask() == null || context.getCheckpoint() == null) {
            return;
        }
        context.getCheckpoint().setCurrentPlan(context.getPlan());
        checkpointHelper.saveCheckpoint(context.getTask(), context.getCheckpoint());
    }

    private String buildDraftItinerary(AgentWorkflowContext context) {
        TravelConstraints constraints = context.getConstraints();
        String destination = constraints.getDestination();
        Integer days = constraints.getDays();
        return "Draft itinerary for " + destination + " (" + days + " days), generated from planner tasks and observed tool results.";
    }

    private String buildFinalItinerary(AgentWorkflowContext context) {
        TravelConstraints constraints = context.getConstraints();
        String destination = constraints.getDestination();
        Integer days = constraints.getDays();
        return "Final itinerary for " + destination + " (" + days + " days), validated from draft itinerary and planner task results.";
    }
}
