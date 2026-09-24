package com.travelagent.model.entity;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class UserMemoryProfile {

    private Long id;
    private Long userId;
    private String sourceSummary;
    private String profileSummary;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime deletedAt;
}
