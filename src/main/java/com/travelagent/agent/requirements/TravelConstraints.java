package com.travelagent.agent.requirements;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@Data
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class TravelConstraints {

    private String rawText;
    private String departure;
    private String destination;
    private LocalDate startDate;
    private LocalDate endDate;
    private Integer days;
    private BigDecimal budgetYuan;
    private Integer peopleCount;
    private List<String> transportPreference = new ArrayList<>();
    private String hotelPreference;
    private List<String> attractionPreference = new ArrayList<>();
    private String foodPreference;
    private String travelPace;
    private List<String> specialGroups = new ArrayList<>();
    private Boolean bookingRequired;
    private List<String> avoid = new ArrayList<>();
    private List<MissingField> missingFields = new ArrayList<>();
    private boolean mustAsk;
    private List<String> updatedFields = new ArrayList<>();

    public TravelConstraints refreshMissingFields() {
        List<MissingField> missing = new ArrayList<>();
        if (isBlank(destination)) {
            missing.add(new MissingField("destination", true, "destination is required for itinerary planning"));
        }
        if (days == null && (startDate == null || endDate == null)) {
            missing.add(new MissingField("days", true, "travel date or trip days is required"));
        }
        if (budgetYuan == null) {
            missing.add(new MissingField("budget", true, "budget is required for budget breakdown and validation"));
        }
        if (peopleCount == null) {
            missing.add(new MissingField("people_count", true, "people count is required for budget and suitability checks"));
        }
        if (isBlank(departure)) {
            missing.add(new MissingField("departure", false, "departure is recommended for inter-city transport estimation"));
        }
        this.missingFields = missing;
        this.mustAsk = missing.stream().anyMatch(MissingField::isRequired);
        return this;
    }

    public TravelConstraints mergeFrom(TravelConstraints update) {
        if (update == null) {
            return refreshMissingFields();
        }
        List<String> changed = new ArrayList<>();
        mergeString("rawText", update.getRawText(), changed);
        mergeString("departure", update.getDeparture(), changed);
        mergeString("destination", update.getDestination(), changed);
        mergeValue("startDate", update.getStartDate(), changed);
        mergeValue("endDate", update.getEndDate(), changed);
        mergeValue("days", update.getDays(), changed);
        mergeValue("budgetYuan", update.getBudgetYuan(), changed);
        mergeValue("peopleCount", update.getPeopleCount(), changed);
        mergeString("hotelPreference", update.getHotelPreference(), changed);
        mergeString("foodPreference", update.getFoodPreference(), changed);
        mergeString("travelPace", update.getTravelPace(), changed);
        mergeValue("bookingRequired", update.getBookingRequired(), changed);
        mergeList("transportPreference", update.getTransportPreference(), changed);
        mergeList("attractionPreference", update.getAttractionPreference(), changed);
        mergeList("specialGroups", update.getSpecialGroups(), changed);
        mergeList("avoid", update.getAvoid(), changed);
        this.updatedFields = changed;
        return refreshMissingFields();
    }

    private void mergeString(String field, String value, List<String> changed) {
        if (isBlank(value)) {
            return;
        }
        switch (field) {
            case "rawText" -> {
                if (!value.equals(rawText)) {
                    rawText = value;
                    changed.add(field);
                }
            }
            case "departure" -> {
                if (!value.equals(departure)) {
                    departure = value;
                    changed.add(field);
                }
            }
            case "destination" -> {
                if (!value.equals(destination)) {
                    destination = value;
                    changed.add(field);
                }
            }
            case "hotelPreference" -> {
                if (!value.equals(hotelPreference)) {
                    hotelPreference = value;
                    changed.add(field);
                }
            }
            case "foodPreference" -> {
                if (!value.equals(foodPreference)) {
                    foodPreference = value;
                    changed.add(field);
                }
            }
            case "travelPace" -> {
                if (!value.equals(travelPace)) {
                    travelPace = value;
                    changed.add(field);
                }
            }
            default -> {
            }
        }
    }

    private void mergeValue(String field, Object value, List<String> changed) {
        if (value == null) {
            return;
        }
        switch (field) {
            case "startDate" -> {
                if (!value.equals(startDate)) {
                    startDate = (LocalDate) value;
                    changed.add(field);
                }
            }
            case "endDate" -> {
                if (!value.equals(endDate)) {
                    endDate = (LocalDate) value;
                    changed.add(field);
                }
            }
            case "days" -> {
                if (!value.equals(days)) {
                    days = (Integer) value;
                    changed.add(field);
                }
            }
            case "budgetYuan" -> {
                if (!value.equals(budgetYuan)) {
                    budgetYuan = (BigDecimal) value;
                    changed.add(field);
                }
            }
            case "peopleCount" -> {
                if (!value.equals(peopleCount)) {
                    peopleCount = (Integer) value;
                    changed.add(field);
                }
            }
            case "bookingRequired" -> {
                if (!value.equals(bookingRequired)) {
                    bookingRequired = (Boolean) value;
                    changed.add(field);
                }
            }
            default -> {
            }
        }
    }

    private void mergeList(String field, List<String> incoming, List<String> changed) {
        if (incoming == null || incoming.isEmpty()) {
            return;
        }
        List<String> target = switch (field) {
            case "transportPreference" -> transportPreference;
            case "attractionPreference" -> attractionPreference;
            case "specialGroups" -> specialGroups;
            case "avoid" -> avoid;
            default -> null;
        };
        if (target == null) {
            return;
        }
        boolean changedList = false;
        for (String item : incoming) {
            if (!isBlank(item) && !target.contains(item.trim())) {
                target.add(item.trim());
                changedList = true;
            }
        }
        if (changedList) {
            changed.add(field);
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}

