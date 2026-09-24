package com.travelagent.agent.accommodation;

import com.travelagent.agent.itinerary.GeneratedItinerary;
import com.travelagent.agent.requirements.TravelConstraints;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class AccommodationAreaAnalyzer {

    public AccommodationAnalysisResult analyze(TravelConstraints constraints, GeneratedItinerary itinerary) {
        if (constraints == null) {
            throw new IllegalArgumentException("constraints are required");
        }
        String destination = constraints.getDestination() == null ? "destination" : constraints.getDestination();
        AccommodationAnalysisResult result = new AccommodationAnalysisResult();
        result.setDestination(destination);
        result.getRecommendedAreas().add(area(destination + " central transit area", 88, 90, 76, 82, peopleFit(constraints),
                "first-time visitors and public-transit travelers",
                List.of("Short commute to most planned stops", "Subway and bus access are prioritized", "Food and commercial support are strong")));
        result.getRecommendedAreas().add(area(destination + " culture district", 78, 82, 84, 80, peopleFit(constraints),
                "culture-focused travelers",
                List.of("Good match for cultural attractions", "Night dining options are easier to arrange", "Budget pressure is moderate")));
        result.getNotRecommendedAreas().add(area(destination + " far suburb area", 42, 45, 90, 70, 55,
                "budget-only fallback",
                List.of("Commute time to core attractions is too long", "Late-night return is less convenient", "Not ideal for elderly or family groups")));
        return result;
    }

    private AccommodationAreaRecommendation area(String name,
                                                 int commute,
                                                 int convenience,
                                                 int budget,
                                                 int safety,
                                                 int peopleFit,
                                                 String suitableFor,
                                                 List<String> reasons) {
        AccommodationAreaRecommendation area = new AccommodationAreaRecommendation();
        area.setAreaName(name);
        area.setCommuteScore(commute);
        area.setConvenienceScore(convenience);
        area.setBudgetScore(budget);
        area.setSafetyScore(safety);
        area.setPeopleFitScore(peopleFit);
        area.setTotalScore((commute + convenience + budget + safety + peopleFit) / 5);
        area.setSuitableFor(suitableFor);
        area.getReasons().addAll(reasons);
        return area;
    }

    private int peopleFit(TravelConstraints constraints) {
        if (constraints.getSpecialGroups() == null || constraints.getSpecialGroups().isEmpty()) {
            return 80;
        }
        String joined = String.join(",", constraints.getSpecialGroups());
        return joined.contains("老人") || joined.contains("儿童") || joined.contains("行动不便") ? 90 : 82;
    }
}
