package com.travelagent.model.dto;

import com.travelagent.agent.requirements.TravelConstraints;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.Map;

public final class TravelRequirementRequests {
    private TravelRequirementRequests() {}

    public record Create(@NotBlank @Size(max = 4000) String text,
                         @Size(max = 64) String timezone,
                         TravelConstraints constraints,
                         String idempotencyKey) {}

    public record Update(@NotNull Integer expectedRevision,
                         String text,
                         String timezone,
                         TravelConstraints constraints) {}

    public record Confirm(@NotNull Integer expectedRevision,
                          @NotBlank @Size(max = 128) String idempotencyKey,
                          Map<String, String> answers) {}

    public record Response(Long draftId, int revision, String status,
                           TravelConstraints constraints, Object questions,
                           String taskUuid) {}
}
