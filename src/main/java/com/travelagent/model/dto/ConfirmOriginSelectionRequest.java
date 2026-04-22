package com.travelagent.model.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class ConfirmOriginSelectionRequest {

    private String pendingInputType;
    private String selectionStage;
    private String selectedBranchType;

    @NotBlank(message = "selected candidate id is required")
    private String selectedCandidateId;

    @NotBlank(message = "selected candidate name is required")
    private String selectedCandidateName;

    private Double selectedLat;
    private Double selectedLng;
}
