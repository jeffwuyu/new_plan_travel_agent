package com.travelagent.model.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class NodeChatRequest {

    private String pendingInputType;
    private String selectionStage;

    @NotBlank(message = "node preference message is required")
    private String message;
}
