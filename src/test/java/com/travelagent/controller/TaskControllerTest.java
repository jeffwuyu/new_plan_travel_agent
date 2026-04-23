package com.travelagent.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.travelagent.exception.BusinessException;
import com.travelagent.exception.GlobalExceptionHandler;
import com.travelagent.exception.QuotaExhaustedException;
import com.travelagent.exception.TaskNotFoundException;
import com.travelagent.filter.JwtAuthInterceptor;
import com.travelagent.model.dto.ConfirmOriginSelectionRequest;
import com.travelagent.model.dto.CreateTaskRequest;
import com.travelagent.model.dto.NodeChatRequest;
import com.travelagent.model.dto.RewindTaskRequest;
import com.travelagent.model.dto.TaskResponse;
import com.travelagent.model.enums.TaskStatus;
import com.travelagent.service.task.TaskProgressService;
import com.travelagent.service.task.TaskService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
@DisplayName("TaskController Tests")
class TaskControllerTest {

    @Mock private TaskService taskService;
    @Mock private TaskProgressService taskProgressService;

    @InjectMocks private TaskController taskController;

    private MockMvc mockMvc;
    private ObjectMapper objectMapper;

    private static final Long USER_ID = 1L;
    private static final int USER_LEVEL = 1;
    private static final String TASK_UUID = "test-uuid-0001";

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(taskController)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
    }

    @Test
    void createTask_validRequest_returns200() throws Exception {
        when(taskService.createTask(eq(USER_ID), eq(USER_LEVEL), any(CreateTaskRequest.class)))
                .thenReturn(buildTaskResponse(TaskStatus.PENDING.getCode()));

        mockMvc.perform(post("/api/tasks")
                        .requestAttr(JwtAuthInterceptor.ATTR_USER_ID, USER_ID)
                        .requestAttr(JwtAuthInterceptor.ATTR_USER_LEVEL, USER_LEVEL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(buildRequest())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.taskUuid").value(TASK_UUID))
                .andExpect(jsonPath("$.data.status").value("pending"));
    }

    @Test
    void createTask_missingStartLocation_returns400() throws Exception {
        CreateTaskRequest req = buildRequest();
        req.setStartLocationQuery("");

        mockMvc.perform(post("/api/tasks")
                        .requestAttr(JwtAuthInterceptor.ATTR_USER_ID, USER_ID)
                        .requestAttr(JwtAuthInterceptor.ATTR_USER_LEVEL, USER_LEVEL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void createTask_quotaExhausted_returns429() throws Exception {
        when(taskService.createTask(anyLong(), anyInt(), any()))
                .thenThrow(new QuotaExhaustedException("daily"));

        mockMvc.perform(post("/api/tasks")
                        .requestAttr(JwtAuthInterceptor.ATTR_USER_ID, USER_ID)
                        .requestAttr(JwtAuthInterceptor.ATTR_USER_LEVEL, USER_LEVEL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(buildRequest())))
                .andExpect(status().isTooManyRequests());
    }

    @Test
    void getTask_exists_returns200() throws Exception {
        when(taskService.getTask(TASK_UUID, USER_ID)).thenReturn(buildTaskResponse(TaskStatus.PLANNING.getCode()));

        mockMvc.perform(get("/api/tasks/{uuid}", TASK_UUID)
                        .requestAttr(JwtAuthInterceptor.ATTR_USER_ID, USER_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.taskUuid").value(TASK_UUID));
    }

    @Test
    void getTask_notFound_returns404() throws Exception {
        when(taskService.getTask(TASK_UUID, USER_ID)).thenThrow(new TaskNotFoundException(TASK_UUID));

        mockMvc.perform(get("/api/tasks/{uuid}", TASK_UUID)
                        .requestAttr(JwtAuthInterceptor.ATTR_USER_ID, USER_ID))
                .andExpect(status().isNotFound());
    }

    @Test
    void listTasks_returns200WithList() throws Exception {
        when(taskService.listTasks(USER_ID)).thenReturn(List.of(
                buildTaskResponse(TaskStatus.PENDING.getCode()),
                buildTaskResponse(TaskStatus.AWAITING_USER_INPUT.getCode())
        ));

        mockMvc.perform(get("/api/tasks")
                        .requestAttr(JwtAuthInterceptor.ATTR_USER_ID, USER_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2));
    }

    @Test
    void cancelTask_pending_returns200() throws Exception {
        doNothing().when(taskService).cancelTask(TASK_UUID, USER_ID);

        mockMvc.perform(delete("/api/tasks/{uuid}", TASK_UUID)
                        .requestAttr(JwtAuthInterceptor.ATTR_USER_ID, USER_ID))
                .andExpect(status().isOk());
    }

    @Test
    void resumeTask_paused_returns200() throws Exception {
        when(taskService.resumeTask(TASK_UUID, USER_ID)).thenReturn(buildTaskResponse(TaskStatus.RESUMING.getCode()));

        mockMvc.perform(post("/api/tasks/{uuid}/resume", TASK_UUID)
                        .requestAttr(JwtAuthInterceptor.ATTR_USER_ID, USER_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("resuming"));
    }

    @Test
    void resumeTask_notPaused_returns400() throws Exception {
        when(taskService.resumeTask(TASK_UUID, USER_ID))
                .thenThrow(new BusinessException(400, "only paused tasks can be resumed"));

        mockMvc.perform(post("/api/tasks/{uuid}/resume", TASK_UUID)
                .requestAttr(JwtAuthInterceptor.ATTR_USER_ID, USER_ID))
                .andExpect(status().isBadRequest());
    }

    @Test
    void confirmSelection_validRequest_returns200() throws Exception {
        when(taskService.confirmOriginSelection(eq(TASK_UUID), eq(USER_ID), any(ConfirmOriginSelectionRequest.class)))
                .thenReturn(buildTaskResponse(TaskStatus.RESUMING.getCode()));

        ConfirmOriginSelectionRequest request = new ConfirmOriginSelectionRequest();
        request.setPendingInputType("attraction_selection");
        request.setSelectedCandidateId("poi-1");
        request.setSelectedCandidateName("Forbidden City");
        request.setSelectedLat(39.9163);
        request.setSelectedLng(116.3972);

        mockMvc.perform(post("/api/tasks/{uuid}/origin-selection", TASK_UUID)
                        .requestAttr(JwtAuthInterceptor.ATTR_USER_ID, USER_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("resuming"));
    }

    @Test
    void rewindTask_validRequest_returns200() throws Exception {
        when(taskService.rewindTask(eq(TASK_UUID), eq(USER_ID), any(RewindTaskRequest.class)))
                .thenReturn(buildTaskResponse(TaskStatus.RESUMING.getCode()));

        RewindTaskRequest request = new RewindTaskRequest();
        request.setTargetStepIndex(1);

        mockMvc.perform(post("/api/tasks/{uuid}/rewind", TASK_UUID)
                        .requestAttr(JwtAuthInterceptor.ATTR_USER_ID, USER_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("resuming"));
    }

    @Test
    void refreshNodeSelection_validRequest_returns200() throws Exception {
        when(taskService.refreshNodeSelection(eq(TASK_UUID), eq(USER_ID), any(NodeChatRequest.class)))
                .thenReturn(buildTaskResponse(TaskStatus.AWAITING_USER_INPUT.getCode()));

        NodeChatRequest request = new NodeChatRequest();
        request.setPendingInputType("poi_candidate_selection");
        request.setSelectionStage("poi_candidate_selection");
        request.setMessage("室内 少走路");

        mockMvc.perform(post("/api/tasks/{uuid}/node-chat", TASK_UUID)
                        .requestAttr(JwtAuthInterceptor.ATTR_USER_ID, USER_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("awaiting_user_input"));
    }

    private TaskResponse buildTaskResponse(String status) {
        TaskResponse r = new TaskResponse();
        r.setTaskUuid(TASK_UUID);
        r.setStatus(status);
        r.setRegion("Beijing");
        r.setStartLocationQuery("Guomao");
        r.setEndLocationQuery("Capital Airport");
        r.setTripStartTime(LocalDateTime.of(2026, 4, 22, 9, 0));
        r.setTripEndTime(LocalDateTime.of(2026, 4, 22, 21, 0));
        r.setFullDayStartTime(LocalTime.of(7, 0));
        r.setFullDayEndTime(LocalTime.of(21, 0));
        r.setTotalTokensUsed(0);
        r.setCreatedAt(LocalDateTime.now());
        return r;
    }

    private CreateTaskRequest buildRequest() {
        CreateTaskRequest req = new CreateTaskRequest();
        req.setRegion("Beijing");
        req.setUserIntent("culture trip");
        req.setStartLocationQuery("Guomao");
        req.setEndLocationQuery("Capital Airport");
        req.setStartTime(LocalDateTime.of(2026, 4, 22, 9, 0));
        req.setEndTime(LocalDateTime.of(2026, 4, 22, 21, 0));
        req.setTravelMode("driving");
        return req;
    }
}
