package com.travelagent.agent.validation;

import com.travelagent.agent.context.CompletedStep;
import com.travelagent.agent.context.TaskCheckpoint;
import com.travelagent.agent.planner.FinalSummaryResult;
import com.travelagent.agent.tools.GeocodeTool;
import com.travelagent.agent.tools.TrafficTimeTool;
import com.travelagent.agent.tools.WeatherTool;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("HallucinationDetector Tests")
class HallucinationDetectorTest {

    private final HallucinationDetector detector = new HallucinationDetector(new HallucinationDetectionProperties());

    @Test
    void phoneClaimWithoutToolEvidenceIsInvalid() {
        TaskCheckpoint checkpoint = checkpointWithToolResults(Map.of(
                GeocodeTool.NAME, Map.of("formattedAddress", "杭州市西湖区")
        ));
        FinalSummaryResult summary = summary("酒店电话 13800138000，建议提前联系。");

        HallucinationDetectionResult result = detector.validateFinalSummary(summary, checkpoint);

        assertThat(result.isValid()).isFalse();
        assertThat(issueCodes(result)).contains("unsupported_phone_claim");
        assertThat(result.getRetryFeedback()).contains("13800138000");
    }

    @Test
    void phoneClaimWithConsumableToolEvidenceIsValid() {
        TaskCheckpoint checkpoint = checkpointWithToolResults(Map.of(
                "booking_query", Map.of("phone", "13800138000")
        ));
        FinalSummaryResult summary = summary("酒店电话 13800138000，建议提前联系。");

        HallucinationDetectionResult result = detector.validateFinalSummary(summary, checkpoint);

        assertThat(result.isValid()).isTrue();
    }

    @Test
    void invalidToolResultCannotSupportPhoneClaim() {
        TaskCheckpoint checkpoint = checkpointWithToolResults(Map.of(
                "booking_query", Map.of(
                        "phone", "13800138000",
                        "available", false,
                        "resultValidation", Map.of("valid", false)
                )
        ));
        FinalSummaryResult summary = summary("酒店电话 13800138000，建议提前联系。");

        HallucinationDetectionResult result = detector.validateFinalSummary(summary, checkpoint);

        assertThat(result.isValid()).isFalse();
        assertThat(issueCodes(result)).contains("unsupported_phone_claim");
    }

    @Test
    void strictFactClaimsWithoutEvidenceAreInvalid() {
        TaskCheckpoint checkpoint = checkpointWithToolResults(Map.of());
        FinalSummaryResult summary = summary("""
                票价 120元，可在 https://example.com 购票。开放时间 09:00-17:00。
                今日晴 26℃，车程 30分钟到达，需预约且有余票。地址：杭州市西湖区龙井路1号。
                """);

        HallucinationDetectionResult result = detector.validateFinalSummary(summary, checkpoint);

        assertThat(result.isValid()).isFalse();
        assertThat(issueCodes(result)).contains(
                "unsupported_price_claim",
                "unsupported_url_claim",
                "unsupported_time_claim",
                "unsupported_weather_claim",
                "unsupported_traffic_claim",
                "unsupported_booking_claim",
                "unsupported_address_claim"
        );
    }

    @Test
    void plainSummaryWithoutStrictClaimsIsValid() {
        TaskCheckpoint checkpoint = checkpointWithToolResults(Map.of());
        FinalSummaryResult summary = summary("这条路线节奏舒展，适合把湖景、街区和城市文化串成一天的轻量体验。");

        HallucinationDetectionResult result = detector.validateFinalSummary(summary, checkpoint);

        assertThat(result.isValid()).isTrue();
    }

    @Test
    void checkpointTimesCanSupportPlannedTimeClaims() {
        CompletedStep step = new CompletedStep();
        step.setStepIndex(0);
        step.setDayNumber(1);
        step.setPlannedStartTime(LocalDateTime.of(2026, 6, 23, 9, 0));
        step.setPlannedEndTime(LocalDateTime.of(2026, 6, 23, 10, 30));
        step.setToolCallResults(Map.of(
                WeatherTool.NAME, Map.of("weather", "晴", "temperature", "26"),
                TrafficTimeTool.NAME, Map.of("durationMin", 30)
        ));
        TaskCheckpoint checkpoint = new TaskCheckpoint();
        checkpoint.setCompletedSteps(List.of(step));
        FinalSummaryResult summary = summary("建议 09:00-10:30 游览，天气晴 26℃，交通约 30分钟。");

        HallucinationDetectionResult result = detector.validateFinalSummary(summary, checkpoint);

        assertThat(result.isValid()).isTrue();
    }

    private TaskCheckpoint checkpointWithToolResults(Map<String, Object> toolResults) {
        CompletedStep step = new CompletedStep();
        step.setStepIndex(0);
        step.setDayNumber(1);
        step.setToolCallResults(toolResults);
        TaskCheckpoint checkpoint = new TaskCheckpoint();
        checkpoint.setCompletedSteps(List.of(step));
        return checkpoint;
    }

    private FinalSummaryResult summary(String description) {
        return new FinalSummaryResult("杭州1日精华游", "湖景与城市文化串联。",
                List.of(new FinalSummaryResult.StepSummary(0, 120, description)));
    }

    private List<String> issueCodes(HallucinationDetectionResult result) {
        return result.getIssues().stream().map(HallucinationDetectionIssue::code).toList();
    }
}
