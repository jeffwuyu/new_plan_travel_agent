package com.travelagent.model.entity;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class UserMemoryFact {

    private Long id;
    private Long userId;
    private String memoryType;
    private String memoryKey;
    private String memoryValue;
    private BigDecimal confidence;
    private Integer evidenceCount;
    private String source;
    private String sourceSummary;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime deletedAt;
}
