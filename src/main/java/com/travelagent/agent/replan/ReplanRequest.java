package com.travelagent.agent.replan;

import com.travelagent.agent.itinerary.GeneratedItinerary;
import com.travelagent.agent.requirements.TravelConstraints;
import com.travelagent.agent.validation.ValidatorIssue;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Data
@NoArgsConstructor
public class ReplanRequest {

    private ReplanTriggerType triggerType;
    private String triggerReason;
    private TravelConstraints revisedConstraints;
    private GeneratedItinerary currentItinerary;
    private List<ValidatorIssue> validatorIssues = new ArrayList<>();
}
