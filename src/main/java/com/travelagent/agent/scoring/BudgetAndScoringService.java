package com.travelagent.agent.scoring;

import com.travelagent.agent.accommodation.AccommodationAnalysisResult;
import com.travelagent.agent.itinerary.DailyItinerary;
import com.travelagent.agent.itinerary.GeneratedItinerary;
import com.travelagent.agent.requirements.TravelConstraints;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;

@Service
public class BudgetAndScoringService {

    public BudgetBreakdown estimateBudget(TravelConstraints constraints, GeneratedItinerary itinerary) {
        int days = itinerary == null ? 1 : Math.max(1, itinerary.getDays());
        BigDecimal total = constraints != null && constraints.getBudgetYuan() != null
                ? constraints.getBudgetYuan()
                : BigDecimal.valueOf(days * 600L);
        BudgetBreakdown budget = new BudgetBreakdown();
        budget.setTicketBudgetYuan(percent(total, 20));
        budget.setTransportBudgetYuan(percent(total, 22));
        budget.setAccommodationBudgetYuan(percent(total, 38));
        budget.setFoodBudgetYuan(percent(total, 16));
        budget.setContingencyBudgetYuan(total.subtract(budget.getTicketBudgetYuan())
                .subtract(budget.getTransportBudgetYuan())
                .subtract(budget.getAccommodationBudgetYuan())
                .subtract(budget.getFoodBudgetYuan()));
        budget.setTotalBudgetYuan(total);
        budget.setDailyBudgetYuan(total.divide(BigDecimal.valueOf(days), 0, RoundingMode.DOWN));
        if (itinerary != null && itinerary.getEstimatedTotalBudgetYuan() != null
                && itinerary.getEstimatedTotalBudgetYuan().compareTo(total) > 0) {
            budget.setOverBudget(true);
            budget.setCompressionSuggestion("Reduce paid attractions or choose a lower-cost accommodation area.");
        }
        return budget;
    }

    public ItineraryScores score(GeneratedItinerary itinerary,
                                 AccommodationAnalysisResult accommodation,
                                 TravelConstraints constraints) {
        ItineraryScores scores = new ItineraryScores();
        int avgStops = 0;
        int avgWalk = 0;
        if (itinerary != null && !itinerary.getDailyPlans().isEmpty()) {
            avgStops = (int) itinerary.getDailyPlans().stream().mapToInt(day -> day.getStops().size()).average().orElse(0);
            avgWalk = (int) itinerary.getDailyPlans().stream().mapToInt(DailyItinerary::getEstimatedWalkingKm).average().orElse(0);
        }
        scores.setIntensityScore(clamp(100 - avgStops * 8 - avgWalk * 3));
        scores.setRouteEfficiencyScore(clamp(85 - Math.max(0, avgStops - 3) * 5));
        scores.setAccommodationMatchScore(accommodation == null || accommodation.getRecommendedAreas().isEmpty()
                ? 70
                : accommodation.getRecommendedAreas().get(0).getTotalScore());
        scores.setWeatherFitScore(80);
        scores.setPeopleFitScore(peopleFit(constraints, avgWalk));
        return scores;
    }

    private BigDecimal percent(BigDecimal value, int percent) {
        return value.multiply(BigDecimal.valueOf(percent)).divide(BigDecimal.valueOf(100), 0, RoundingMode.DOWN);
    }

    private int peopleFit(TravelConstraints constraints, int avgWalk) {
        if (constraints == null || constraints.getSpecialGroups() == null || constraints.getSpecialGroups().isEmpty()) {
            return clamp(90 - avgWalk * 2);
        }
        return clamp(95 - avgWalk * 4);
    }

    private int clamp(int value) {
        return Math.max(0, Math.min(100, value));
    }
}
