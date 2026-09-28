package com.travelagent.agent.requirements;

import com.travelagent.model.dto.CreateTaskRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("TravelRequirementParser Tests")
class TravelRequirementParserTest {

    private final TravelRequirementParser parser = new TravelRequirementParser();

    @Test
    void parseFullDomesticTripRequest_returnsStructuredConstraints() {
        TravelConstraints constraints = parser.parse(
                "我和朋友两个人从上海去北京玩三天，预算 3000 元，想看历史景点和吃本地美食，行程轻松一点，不想太早起。");

        assertThat(constraints.getDeparture()).isEqualTo("上海");
        assertThat(constraints.getDestination()).isEqualTo("北京");
        assertThat(constraints.getDays()).isEqualTo(3);
        assertThat(constraints.getBudgetYuan()).isEqualByComparingTo("3000");
        assertThat(constraints.getPeopleCount()).isEqualTo(2);
        assertThat(constraints.getTravelPace()).isEqualTo("relaxed");
        assertThat(constraints.getAttractionPreference()).contains("历史");
        assertThat(constraints.getFoodPreference()).contains("本地美食");
        assertThat(constraints.isMustAsk()).isFalse();
    }

    @Test
    void parseMissingRequiredFields_marksFollowUpFields() {
        TravelConstraints constraints = parser.parse("想轻松一点，住得方便一点");

        Map<String, Boolean> missing = constraints.getMissingFields().stream()
                .collect(Collectors.toMap(MissingField::getName, MissingField::isRequired));

        assertThat(missing).containsEntry("destination", true);
        assertThat(missing).containsEntry("days", true);
        assertThat(missing).containsEntry("budget", true);
        assertThat(missing).containsEntry("people_count", true);
        assertThat(missing).containsEntry("departure", false);
        assertThat(constraints.isMustAsk()).isTrue();
    }

    @Test
    void parseWithExistingConstraints_updatesOnlyNewValues() {
        TravelConstraints existing = new TravelConstraints();
        existing.setDeparture("上海");
        existing.setDestination("北京");
        existing.setDays(3);
        existing.setBudgetYuan(new BigDecimal("3000"));
        existing.setPeopleCount(2);
        existing.getAttractionPreference().add("历史");
        existing.refreshMissingFields();

        TravelConstraints updated = parser.parse("刚才预算太高了，改成 2000 元以内，少走路", existing);

        assertThat(updated.getDestination()).isEqualTo("北京");
        assertThat(updated.getBudgetYuan()).isEqualByComparingTo("2000");
        assertThat(updated.getTravelPace()).isEqualTo("relaxed");
        assertThat(updated.getAvoid()).contains("走路");
        assertThat(updated.getUpdatedFields()).contains("budgetYuan");
        assertThat(updated.isMustAsk()).isFalse();
    }

    @Test
    void fromCreateTaskRequest_mapsExistingTaskInputToUnifiedConstraints() {
        CreateTaskRequest request = new CreateTaskRequest();
        request.setRegion("杭州");
        request.setUserIntent("带老人少走路");
        request.setStartLocationQuery("上海");
        request.setStartTime(LocalDateTime.of(2026, 7, 1, 9, 0));
        request.setEndTime(LocalDateTime.of(2026, 7, 2, 18, 0));
        request.setTotalBudgetYuan(new BigDecimal("2500"));
        request.setAdultCount(3);

        TravelConstraints constraints = parser.fromCreateTaskRequest(request);

        assertThat(constraints.getDestination()).isEqualTo("杭州");
        assertThat(constraints.getDeparture()).isEqualTo("上海");
        assertThat(constraints.getStartDate()).isEqualTo(LocalDate.of(2026, 7, 1));
        assertThat(constraints.getEndDate()).isEqualTo(LocalDate.of(2026, 7, 2));
        assertThat(constraints.getDays()).isEqualTo(2);
        assertThat(constraints.getBudgetYuan()).isEqualByComparingTo("2500");
        assertThat(constraints.getPeopleCount()).isEqualTo(3);
        assertThat(constraints.isMustAsk()).isFalse();
    }
}
