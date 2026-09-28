package com.travelagent.agent.tools;

import com.travelagent.client.web.WebSearchClient;
import com.travelagent.client.web.WebSearchQuery;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("BookingQueryTool Tests")
class BookingQueryToolTest {

    @Mock private WebSearchClient webSearchClient;

    @InjectMocks
    private BookingQueryTool bookingQueryTool;

    @Test
    @DisplayName("getName returns booking_query")
    void getName_returnsCorrectName() {
        assertThat(bookingQueryTool.getName()).isEqualTo("booking_query");
        assertThat(bookingQueryTool.getSource()).isEqualTo("web-search:booking");
    }

    @Test
    @DisplayName("execute summarizes official booking, ticket and safety boundaries")
    @SuppressWarnings("unchecked")
    void execute_buildsBookingSummaryAndSafetyPolicy() {
        when(webSearchClient.search(any())).thenReturn(List.of(
                Map.of(
                        "title", "第三方攻略：故宫门票",
                        "url", "https://example.com/dpm-ticket",
                        "snippet", "建议提前规划，成人票 60元"
                ),
                Map.of(
                        "title", "故宫博物院官方预约购票",
                        "url", "https://www.dpm.org.cn/visit/ticket",
                        "source", "景区官网",
                        "snippet", "故宫博物院门票需实名预约，成人票60元，可预约2026-06-10 09:00-12:00"
                )
        ));

        Map<String, Object> result = bookingQueryTool.execute(Map.of(
                "attraction", "故宫博物院",
                "city", "北京",
                "date", "2026-06-10",
                "bookingRequired", true,
                "ticketPreference", "成人票",
                "topK", 3
        ), "task-uuid-step5-booking_query");

        assertThat(result).containsEntry("available", true);
        assertThat(result).containsEntry("attraction", "故宫博物院");
        assertThat(result).containsEntry("city", "北京");
        assertThat(result).containsEntry("bookingRequired", true);
        assertThat(result).containsEntry("availabilityStatus", "AVAILABLE");
        assertThat(result).containsEntry("manualActionRequired", true);
        assertThat(result).containsKeys(
                "officialEntry", "ticketPriceSummary", "ticketTypeRecommendations",
                "bookingFlow", "requiredUserPreparation", "safetyPolicy", "alternatives", "queryTime", "cacheTtl");

        Map<String, Object> officialEntry = (Map<String, Object>) result.get("officialEntry");
        assertThat(officialEntry).containsEntry("url", "https://www.dpm.org.cn/visit/ticket");

        Map<String, Object> ticketSummary = (Map<String, Object>) result.get("ticketPriceSummary");
        assertThat((List<String>) ticketSummary.get("prices")).contains("成人票 60元");

        assertThat((List<String>) result.get("reservableTimeSlots")).contains("2026-06-10 09:00-12:00");

        Map<String, Object> safetyPolicy = (Map<String, Object>) result.get("safetyPolicy");
        assertThat(safetyPolicy)
                .containsEntry("autoFillSensitiveInfo", false)
                .containsEntry("autoSubmitOrder", false)
                .containsEntry("autoPayment", false);
        assertThat((List<String>) safetyPolicy.get("sensitiveFields")).contains("身份证号", "验证码", "支付信息");
        assertThat((List<String>) safetyPolicy.get("prohibitedActions")).contains("自动提交订单", "自动支付");

        ArgumentCaptor<WebSearchQuery> queryCaptor = ArgumentCaptor.forClass(WebSearchQuery.class);
        verify(webSearchClient).search(queryCaptor.capture());
        assertThat(queryCaptor.getValue().getAttraction()).isEqualTo("故宫博物院");
        assertThat(queryCaptor.getValue().getInfoType()).isEqualTo("预约购票");
        assertThat(queryCaptor.getValue().getQueryText()).contains("官方", "预约", "门票");
        assertThat(queryCaptor.getValue().getTopK()).isEqualTo(3);
    }

    @Test
    @DisplayName("execute recommends alternatives when booking is unavailable")
    @SuppressWarnings("unchecked")
    void execute_unavailableBookingReturnsAlternatives() {
        when(webSearchClient.search(any())).thenReturn(List.of(
                Map.of(
                        "title", "景区官网：预约名额已满",
                        "url", "https://example.gov.cn/ticket",
                        "source", "官方网站",
                        "snippet", "今日门票约满，暂停预约，建议选择其他日期或人工咨询入口"
                )
        ));

        Map<String, Object> result = bookingQueryTool.execute(Map.of(
                "attractionName", "热门景区",
                "destination", "杭州",
                "date", "2026-07-01"
        ), "task-uuid-step5-booking_query");

        assertThat(result).containsEntry("availabilityStatus", "UNAVAILABLE");
        Map<String, Object> alternatives = (Map<String, Object>) result.get("alternatives");
        assertThat(alternatives).containsEntry("needed", true);
        assertThat((List<String>) alternatives.get("alternativeDates")).isNotEmpty();
        assertThat((List<String>) alternatives.get("alternativeAttractions")).contains("选择同城相似主题景点");
        assertThat(String.valueOf(alternatives.get("manualReservationEntry"))).contains("热门景区");
        assertThat(String.valueOf(result.get("uncertaintyNote"))).contains("官方");
    }
}
