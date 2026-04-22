package com.travelagent.model.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class SelectionOptionItem {

    private String optionId;
    private String label;
    private String description;
    private String branchType;
}
