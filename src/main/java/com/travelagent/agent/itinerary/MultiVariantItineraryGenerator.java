package com.travelagent.agent.itinerary;

import com.travelagent.agent.requirements.TravelConstraints;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

@Service
public class MultiVariantItineraryGenerator {

    private final ItineraryGenerator itineraryGenerator;

    public MultiVariantItineraryGenerator(ItineraryGenerator itineraryGenerator) {
        this.itineraryGenerator = itineraryGenerator;
    }

    public List<ItineraryVariant> generateVariants(ItineraryGenerationInput input) {
        if (input == null || input.getConstraints() == null) {
            throw new IllegalArgumentException("constraints are required");
        }
        List<ItineraryVariant> variants = new ArrayList<>();
        variants.add(buildVariant(input, "relaxed", "More rest time, fewer stops and lower walking pressure."));
        variants.add(buildVariant(input, "classic", "Balanced coverage of core attractions, budget and route efficiency."));
        variants.add(buildVariant(input, "deep", "More cultural explanation, niche stops and denser route coverage."));
        return variants;
    }

    public ItineraryVariant refineVariant(ItineraryVariant variant, String feedback) {
        if (variant == null) {
            throw new IllegalArgumentException("variant is required");
        }
        ItineraryVariant refined = variant.copy();
        refined.getDifferenceNotes().add("Refined from user feedback: " + (feedback == null ? "" : feedback.trim()));
        refined.setSelectedForDetail(true);
        return refined;
    }

    private ItineraryVariant buildVariant(ItineraryGenerationInput original, String type, String note) {
        ItineraryGenerationInput copied = new ItineraryGenerationInput();
        TravelConstraints constraints = new TravelConstraints();
        constraints.mergeFrom(original.getConstraints());
        constraints.setTravelPace(type.equals("classic") ? "normal" : type.equals("deep") ? "intensive" : "relaxed");
        copied.setConstraints(constraints.refreshMissingFields());
        copied.setPreference(original.getPreference());
        copied.setToolResults(original.getToolResults());
        copied.setRagContext(original.getRagContext());
        copied.setValidatorConstraints(original.getValidatorConstraints());

        GeneratedItinerary itinerary = itineraryGenerator.generate(copied);
        ItineraryVariant variant = new ItineraryVariant();
        variant.setVariantType(type);
        variant.setItinerary(itinerary);
        variant.setIntensityScore(type.equals("deep") ? 85 : type.equals("classic") ? 65 : 40);
        variant.setBudgetEstimateYuan(itinerary.getEstimatedTotalBudgetYuan());
        if ("relaxed".equals(type) && variant.getBudgetEstimateYuan() != null) {
            variant.setBudgetEstimateYuan(variant.getBudgetEstimateYuan().subtract(BigDecimal.valueOf(100)).max(BigDecimal.ZERO));
        }
        variant.getDifferenceNotes().add(note);
        variant.getDifferenceNotes().add("Accommodation and transport pressure are adjusted for " + type + " pace.");
        variant.setSuitableFor(type.equals("relaxed") ? "families, elderly travelers, slow travel" :
                type.equals("deep") ? "culture lovers and high-energy travelers" : "first-time visitors");
        return variant;
    }
}
