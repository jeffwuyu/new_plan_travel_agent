package com.travelagent.agent.requirements;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class MissingField {

    private String name;
    private boolean required;
    private String reason;
}

