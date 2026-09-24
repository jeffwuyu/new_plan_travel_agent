package com.travelagent.controller;

import com.travelagent.agent.itinerary.GeneratedItinerary;
import com.travelagent.agent.itinerary.ItineraryGenerationInput;
import com.travelagent.agent.itinerary.ItineraryGenerator;
import com.travelagent.agent.itinerary.ItineraryVariant;
import com.travelagent.agent.itinerary.MultiVariantItineraryGenerator;
import com.travelagent.agent.memory.UserPreference;
import com.travelagent.agent.memory.UserPreferenceMemoryService;
import com.travelagent.agent.orchestration.AgentWorkflowContext;
import com.travelagent.agent.orchestration.SpringAiAgentOrchestrator;
import com.travelagent.agent.planner.TravelPlan;
import com.travelagent.agent.requirements.TravelConstraints;
import com.travelagent.agent.requirements.TravelRequirementParser;
import com.travelagent.agent.trace.AgentTraceRecorder;
import com.travelagent.agent.trace.TraceSnapshot;
import com.travelagent.model.dto.AgentFeedbackRequest;
import com.travelagent.model.dto.AgentSessionRequest;
import com.travelagent.model.dto.Result;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/agent")
@Tag(name = "实验 Agent 会话接口", description = "内部/实验编排接口，不作为前端生产主流程契约；生产任务流请使用 /api/tasks。")
@ConditionalOnProperty(prefix = "agent.session-api", name = "enabled", havingValue = "true")
public class TravelAgentApiController {

    private final TravelRequirementParser requirementParser;
    private final SpringAiAgentOrchestrator orchestrator;
    private final ItineraryGenerator itineraryGenerator;
    private final MultiVariantItineraryGenerator variantGenerator;
    private final UserPreferenceMemoryService memoryService;
    private final AgentTraceRecorder traceRecorder;
    private final Map<String, AgentSessionView> sessions = new LinkedHashMap<>();

    public TravelAgentApiController(TravelRequirementParser requirementParser,
                                    SpringAiAgentOrchestrator orchestrator,
                                    ItineraryGenerator itineraryGenerator,
                                    MultiVariantItineraryGenerator variantGenerator,
                                    UserPreferenceMemoryService memoryService,
                                    AgentTraceRecorder traceRecorder) {
        this.requirementParser = requirementParser;
        this.orchestrator = orchestrator;
        this.itineraryGenerator = itineraryGenerator;
        this.variantGenerator = variantGenerator;
        this.memoryService = memoryService;
        this.traceRecorder = traceRecorder;
    }

    @PostMapping("/sessions")
    public Result<AgentSessionView> createSession(@RequestBody(required = false) AgentSessionRequest request) {
        String sessionId = UUID.randomUUID().toString();
        AgentSessionView view = new AgentSessionView(sessionId);
        if (request != null && request.getMessage() != null && !request.getMessage().isBlank()) {
            view = submitInternal(sessionId, request);
        } else {
            sessions.put(sessionId, view);
        }
        return Result.success(view);
    }

    @PostMapping("/sessions/{sessionId}/requirements")
    public Result<AgentSessionView> submitRequirement(@PathVariable String sessionId,
                                                      @RequestBody AgentSessionRequest request) {
        return Result.success(submitInternal(sessionId, request));
    }

    @GetMapping("/sessions/{sessionId}/plan")
    public Result<TravelPlan> getPlan(@PathVariable String sessionId) {
        return Result.success(requireSession(sessionId).plan());
    }

    @GetMapping("/sessions/{sessionId}/itinerary")
    public Result<GeneratedItinerary> getItinerary(@PathVariable String sessionId) {
        return Result.success(requireSession(sessionId).itinerary());
    }

    @PostMapping("/sessions/{sessionId}/feedback")
    public Result<AgentSessionView> submitFeedback(@PathVariable String sessionId,
                                                   @RequestBody AgentFeedbackRequest request) {
        AgentSessionView current = requireSession(sessionId);
        current.feedback().add(request == null ? "" : request.getMessage());
        sessions.put(sessionId, current);
        return Result.success(current);
    }

    @GetMapping("/users/{userId}/preferences")
    public Result<UserPreference> getUserPreference(@PathVariable Long userId) {
        return Result.success(memoryService.loadRelevantPreference(userId, null));
    }

    @GetMapping("/sessions/{sessionId}/trace")
    public Result<TraceSnapshot> getTrace(@PathVariable String sessionId) {
        return Result.success(requireSession(sessionId).trace());
    }

    @GetMapping("/health")
    public Result<Map<String, String>> health() {
        return Result.success(Map.of("status", "UP", "service", "travel-agent-api"));
    }

    private AgentSessionView submitInternal(String sessionId, AgentSessionRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("request is required");
        }
        TravelConstraints constraints = request.getConstraints() == null
                ? requirementParser.parse(request.getMessage())
                : request.getConstraints().refreshMissingFields();
        String workflowMessage = workflowMessage(request, constraints);
        AgentWorkflowContext workflow = orchestrator.execute(new AgentWorkflowContext(workflowMessage));
        UserPreference preference = memoryService.upsertFromConstraints(0L, constraints, request.getMessage());
        ItineraryGenerationInput input = new ItineraryGenerationInput();
        input.setConstraints(constraints);
        input.setPreference(preference);
        GeneratedItinerary itinerary = itineraryGenerator.generate(input);
        List<ItineraryVariant> variants = variantGenerator.generateVariants(input);
        TraceSnapshot trace = traceRecorder.fromWorkflow(workflow);
        AgentSessionView view = new AgentSessionView(sessionId, constraints, workflow.getPlan(), itinerary, variants,
                workflow.isComplete() ? "complete" : "failed", trace, Instant.now().toString(), new java.util.ArrayList<>());
        sessions.put(sessionId, view);
        return view;
    }

    private String workflowMessage(AgentSessionRequest request, TravelConstraints constraints) {
        if (request.getMessage() != null && !request.getMessage().isBlank()) {
            return request.getMessage();
        }
        if (constraints == null) {
            return "";
        }
        return nullToEmpty(constraints.getPeopleCount()) + "个人从"
                + nullToEmpty(constraints.getDeparture()) + "去"
                + nullToEmpty(constraints.getDestination()) + "玩"
                + nullToEmpty(constraints.getDays()) + "天，预算"
                + nullToEmpty(constraints.getBudgetYuan()) + "元。";
    }

    private String nullToEmpty(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private AgentSessionView requireSession(String sessionId) {
        AgentSessionView view = sessions.get(sessionId);
        if (view == null) {
            throw new IllegalArgumentException("session not found: " + sessionId);
        }
        return view;
    }

    public record AgentSessionView(String sessionId,
                                   TravelConstraints constraints,
                                   TravelPlan plan,
                                   GeneratedItinerary itinerary,
                                   List<ItineraryVariant> variants,
                                   String status,
                                   TraceSnapshot trace,
                                   String updatedAt,
                                   List<String> feedback) {

        public AgentSessionView(String sessionId) {
            this(sessionId, null, null, null, List.of(), "created", null, Instant.now().toString(), new java.util.ArrayList<>());
        }
    }
}
