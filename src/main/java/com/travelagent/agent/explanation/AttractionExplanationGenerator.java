package com.travelagent.agent.explanation;

import com.travelagent.service.rag.RagSearchResult;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Service
public class AttractionExplanationGenerator {

    public AttractionExplanation generate(String attractionName, List<String> interests, List<RagSearchResult> ragResults) {
        if (attractionName == null || attractionName.isBlank()) {
            throw new IllegalArgumentException("attractionName is required");
        }
        AttractionExplanation explanation = new AttractionExplanation();
        explanation.setAttractionName(attractionName);
        String interestText = interests == null || interests.isEmpty() ? "history and local culture" : String.join(", ", interests);
        if (ragResults == null || ragResults.isEmpty()) {
            explanation.setContent(attractionName + " explanation focuses on " + interestText
                    + ". Knowledge base evidence is limited, so realtime facts should be verified by official sources.");
            explanation.setUncertaintyNote("RAG recall is empty; explanation is intentionally conservative.");
            return explanation;
        }
        String snippets = ragResults.stream()
                .limit(3)
                .map(RagSearchResult::chunkText)
                .filter(text -> text != null && !text.isBlank())
                .map(text -> text.length() > 80 ? text.substring(0, 80) : text)
                .collect(Collectors.joining(" "));
        explanation.setContent(attractionName + " can be explained through " + interestText + ": " + snippets);
        ragResults.stream()
                .limit(3)
                .map(result -> result.sourceName() + " " + result.sourceUrl())
                .forEach(explanation.getSourceNotes()::add);
        return explanation;
    }
}
