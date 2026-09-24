package com.travelagent.agent.scoring;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@NoArgsConstructor
public class BudgetBreakdown {

    private BigDecimal ticketBudgetYuan = BigDecimal.ZERO;
    private BigDecimal transportBudgetYuan = BigDecimal.ZERO;
    private BigDecimal accommodationBudgetYuan = BigDecimal.ZERO;
    private BigDecimal foodBudgetYuan = BigDecimal.ZERO;
    private BigDecimal contingencyBudgetYuan = BigDecimal.ZERO;
    private BigDecimal dailyBudgetYuan = BigDecimal.ZERO;
    private BigDecimal totalBudgetYuan = BigDecimal.ZERO;
    private boolean overBudget;
    private String compressionSuggestion;
}
