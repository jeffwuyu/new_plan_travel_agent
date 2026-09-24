package com.travelagent.agent.itinerary;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

@Data
@NoArgsConstructor
public class ItineraryVariant {

    private String variantType;
    private GeneratedItinerary itinerary;
    private BigDecimal budgetEstimateYuan;
    private int intensityScore;
    private String suitableFor;
    private boolean selectedForDetail;
    private List<String> differenceNotes = new ArrayList<>();

    public ItineraryVariant copy() {
        ItineraryVariant copy = new ItineraryVariant();
        copy.setVariantType(variantType);
        copy.setItinerary(itinerary);
        copy.setBudgetEstimateYuan(budgetEstimateYuan);
        copy.setIntensityScore(intensityScore);
        copy.setSuitableFor(suitableFor);
        copy.setSelectedForDetail(selectedForDetail);
        copy.setDifferenceNotes(new ArrayList<>(differenceNotes));
        return copy;
    }
}
