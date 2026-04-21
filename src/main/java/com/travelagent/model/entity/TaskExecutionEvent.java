package com.travelagent.model.entity;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
public class TaskExecutionEvent {

    private Long          id;
    private String        taskUuid;
    private String        eventType;
    private String        status;
    private Integer       stepIndex;
    private Integer       totalSteps;
    private String        message;
    private String        detailsJson;
    private LocalDateTime createdAt;
}
