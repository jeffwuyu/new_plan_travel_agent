package com.travelagent.agent.validation;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ValidatorIssue {

    private String type;
    private String message;
    private String suggestion;
}
