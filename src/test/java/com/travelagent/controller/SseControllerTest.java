package com.travelagent.controller;

import com.travelagent.exception.GlobalExceptionHandler;
import com.travelagent.filter.JwtAuthInterceptor;
import com.travelagent.mapper.TaskMapper;
import com.travelagent.model.entity.Task;
import com.travelagent.service.notification.SseNotificationService;
import com.travelagent.service.notification.SseTicketService;
import com.travelagent.service.task.TaskProgressService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
@DisplayName("SseController Tests")
class SseControllerTest {

    @Mock
    private SseNotificationService sseNotificationService;

    @Mock
    private TaskMapper taskMapper;

    @Mock
    private TaskProgressService taskProgressService;

    @Mock
    private SseTicketService sseTicketService;

    @InjectMocks
    private SseController sseController;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(sseController)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    @DisplayName("task owner can issue stream ticket")
    void issueStreamTicket_owner_returns200() throws Exception {
        Task task = new Task();
        task.setTaskUuid("task-123");
        task.setUserId(7L);
        when(taskMapper.findByUuid("task-123")).thenReturn(task);
        when(sseTicketService.issue(7L, "task-123")).thenReturn("ticket-abc");

        mockMvc.perform(post("/api/tasks/task-123/stream-ticket")
                        .requestAttr(JwtAuthInterceptor.ATTR_USER_ID, 7L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.ticket").value("ticket-abc"))
                .andExpect(jsonPath("$.data.queryParam").value("sseTicket"));

        verify(sseTicketService).issue(7L, "task-123");
    }

    @Test
    @DisplayName("non-owner cannot issue stream ticket")
    void issueStreamTicket_nonOwner_returns403() throws Exception {
        Task task = new Task();
        task.setTaskUuid("task-123");
        task.setUserId(7L);
        when(taskMapper.findByUuid("task-123")).thenReturn(task);

        mockMvc.perform(post("/api/tasks/task-123/stream-ticket")
                        .requestAttr(JwtAuthInterceptor.ATTR_USER_ID, 8L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(403));
    }
}
