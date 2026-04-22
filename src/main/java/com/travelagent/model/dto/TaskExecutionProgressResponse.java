package com.travelagent.model.dto;

import com.travelagent.model.entity.TaskExecutionEvent;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Data
@NoArgsConstructor
public class TaskExecutionProgressResponse {

    private String taskUuid;
    private String currentStatus;
    private Integer currentStepIndex;
    private Integer totalSteps;
    private List<TaskExecutionEvent> events;
    private int totalEventCount;
    private Boolean awaitingUserInput = false;
    private String pendingInputType;
    private String pauseReason;
    private List<LocationCandidateItem> locationCandidates = new ArrayList<>();
    private ResolvedLocation selectedOrigin;
    private ResolvedLocation selectedDestination;
}
