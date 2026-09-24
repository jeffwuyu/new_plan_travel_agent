package com.travelagent.agent.explanation;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Data
@NoArgsConstructor
public class AttractionExplanation {

    private String attractionName;
    private String content;
    private List<String> sourceNotes = new ArrayList<>();
    private String uncertaintyNote;
}
