package com.travelagent.agent.validation;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Data
@NoArgsConstructor
public class ValidatorResult {

    private boolean valid = true;
    private List<ValidatorIssue> issues = new ArrayList<>();

    public void addIssue(String type, String message, String suggestion) {
        valid = false;
        issues.add(new ValidatorIssue(type, message, suggestion));
    }
}
