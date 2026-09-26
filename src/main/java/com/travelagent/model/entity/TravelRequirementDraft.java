package com.travelagent.model.entity;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class TravelRequirementDraft {
    private Long id;
    private Long userId;
    private int revision;
    private String status;
    private String rawText;
    private String constraintsJson;
    private String questionsJson;
    private String idempotencyKey;
    private String confirmationIdempotencyKey;
    private String confirmationSnapshotJson;
    private String timezone;
    private String answersJson;
    private String taskUuid;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
