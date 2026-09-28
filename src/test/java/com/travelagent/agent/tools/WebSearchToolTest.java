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
@DisplayName("WebSearchTool Tests")
class WebSearchToolTest {

    @Mock private WebSearchClient webSearchClient;

    @InjectMocks
    private WebSearchTool webSearchTool;

    @Test
    @DisplayName("getName returns web_search")
    void getName_returnsCorrectName() {
        assertThat(webSearchTool.getName()).isEqualTo("web_search");
        assertThat(webSearchTool.getSource()).isEqualTo("web-search");
    }

    @Test
    @DisplayName("execute builds scenic query and ranks official sources first")
    void execute_ranksOfficialSourcesAndReturnsQueryMetadata() {
        when(webSearchClient.search(any())).thenReturn(List.of(
                Map.of(
                        "title", "第三方攻略：故宫开放时间",
                        "url", "https://example.com/forbidden-city",
                        "snippet", "故宫通常开放，节假日人流较多"
                ),
                Map.of(
                        "title", "故宫博物院官方预约公告",
                        "url", "https://www.dpm.org.cn/visit/ticket",
                        "source", "景区官网",
                        "snippet", "故宫博物院门票需实名预约，周一闭馆"
                )
        ));

        Map<String, Object> result = webSearchTool.execute(Map.of(
                "attraction", "故宫博物院",
                "city", "北京",
                "date", "2026-06-10",
                "infoType", "开放时间和预约规则",
                "topK", 3
        ), "task-uuid-step1-web_search");

        assertThat(result).containsEntry("available", true);
        assertThat(result).containsEntry("attraction", "故宫博物院");
        assertThat(result).containsEntry("city", "北京");
        assertThat(result).containsEntry("date", "2026-06-10");
        assertThat(result).containsEntry("infoType", "开放时间和预约规则");
        assertThat(result).containsEntry("source", "web-search");
        assertThat(result).containsKeys("queryTime", "summary", "sources", "uncertaintyNote");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> sources = (List<Map<String, Object>>) result.get("sources");
        assertThat(sources).hasSize(2);
        assertThat(sources.get(0)).containsEntry("trustedSource", true);
        assertThat(sources.get(0).get("title")).isEqualTo("故宫博物院官方预约公告");

        ArgumentCaptor<WebSearchQuery> queryCaptor = ArgumentCaptor.forClass(WebSearchQuery.class);
        verify(webSearchClient).search(queryCaptor.capture());
        assertThat(queryCaptor.getValue().getAttraction()).isEqualTo("故宫博物院");
        assertThat(queryCaptor.getValue().getCity()).isEqualTo("北京");
        assertThat(queryCaptor.getValue().getTopK()).isEqualTo(3);
    }

    @Test
    @DisplayName("execute marks uncertainty when sources conflict")
    void execute_conflictingSourcesMarksUncertainty() {
        when(webSearchClient.search(any())).thenReturn(List.of(
                Map.of("title", "景区官网：今日开放", "url", "https://example.gov.cn/a", "snippet", "景区今日开放"),
                Map.of("title", "临时闭园通知", "url", "https://news.example/a", "snippet", "受维修影响临时闭园")
        ));

        Map<String, Object> result = webSearchTool.execute(Map.of(
                "query", "某景区 临时闭园"
        ), "task-uuid-step2-web_search");

        assertThat(result).containsEntry("conflictDetected", true);
        assertThat(String.valueOf(result.get("uncertaintyNote"))).contains("冲突");
    }
}
