package com.travelagent.agent.replan;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Data
@NoArgsConstructor
public class ReplanResult {

    private boolean fullRegenerationRequired;
    private String reason;
    private List<String> localAdjustments = new ArrayList<>();
    private List<String> planVersionNotes = new ArrayList<>();
    private boolean validatorShouldRunAgain = true;
}
