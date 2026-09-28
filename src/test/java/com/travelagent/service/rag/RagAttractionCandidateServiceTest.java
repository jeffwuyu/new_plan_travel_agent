package com.travelagent.service.rag;

import com.travelagent.agent.context.CompletedStep;
import com.travelagent.agent.context.PlanningConfig;
import com.travelagent.agent.context.TaskCheckpoint;
import com.travelagent.agent.planner.PlanNextAttractionRequest;
import com.travelagent.agent.tools.GeocodeTool;
import com.travelagent.agent.tools.TrafficTimeTool;
import com.travelagent.model.dto.LocationCandidateItem;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("RagAttractionCandidateService Tests")
class RagAttractionCandidateServiceTest {

    @Mock private RagService ragService;
    @Mock private GeocodeTool geocodeTool;
    @Mock private TrafficTimeTool trafficTimeTool;

    @InjectMocks
    private RagAttractionCandidateService service;

    @Test
    void buildCandidates_usesTop20RagAndReturnsTop3ByDistanceAndTime() {
        TaskCheckpoint cp = checkpoint();
        PlanNextAttractionRequest request = request(480);
        when(ragService.queryChunks(anyString(), eq("Hangzhou"), eq(20))).thenReturn(List.of(
                "Attraction: Far Park. Outdoor lake view.",
                "Attraction: Near Museum. Indoor culture.",
                "Attraction: Mid Gallery. Indoor art.",
                "Attraction: Close Market. Food street."
        ));
        mockGeo("Far Park", 31.20, 121.00);
        mockGeo("Near Museum", 30.251, 120.141);
        mockGeo("Mid Gallery", 30.30, 120.20);
        mockGeo("Close Market", 30.26, 120.15);
        mockTraffic(90, 8, 30, 12);

        List<LocationCandidateItem> candidates = service.buildCandidates(cp, request, Map.of(), "uuid");

        assertThat(candidates).hasSize(3);
        assertThat(candidates).extracting(LocationCandidateItem::getName)
                .contains("Near Museum", "Close Market", "Mid Gallery")
                .doesNotContain("Far Park");
        assertThat(candidates).allSatisfy(item -> {
            assertThat(item.getCandidateType()).isEqualTo("rag_candidate");
            assertThat(item.getBranchType()).isEqualTo("rag_route");
        });
    }

    @Test
    void buildCandidates_filtersVisitedAndBudgetOverflow() {
        TaskCheckpoint cp = checkpoint();
        CompletedStep visited = new CompletedStep();
        visited.setAttractionName("Near Museum");
        cp.setCompletedSteps(new ArrayList<>(List.of(visited)));
        PlanNextAttractionRequest request = request(140);
        request.setVisitedPoiNames(List.of("Near Museum"));
        when(ragService.queryChunks(anyString(), eq("Hangzhou"), eq(20))).thenReturn(List.of(
                "Attraction: Near Museum. Indoor culture.",
                "Attraction: Far Park. Outdoor lake view.",
                "Attraction: Close Market. Food street."
        ));
        mockGeo("Far Park", 31.20, 121.00);
        mockGeo("Close Market", 30.26, 120.15);
        mockTraffic(90, 12);

        List<LocationCandidateItem> candidates = service.buildCandidates(cp, request, Map.of(), "uuid");

        assertThat(candidates).extracting(LocationCandidateItem::getName)
                .containsExactly("Close Market");
    }

    @Test
    void buildCandidates_prefersIndoorWhenWeatherRequiresIt() {
        TaskCheckpoint cp = checkpoint();
        PlanNextAttractionRequest request = request(480);
        when(ragService.queryChunks(anyString(), eq("Hangzhou"), eq(20))).thenReturn(List.of(
                "Attraction: Rain Museum. Indoor museum and exhibition.",
                "Attraction: Rain Park. Outdoor park and lake."
        ));
        mockGeo("Rain Museum", 30.30, 120.20);
        mockGeo("Rain Park", 30.30, 120.20);
        mockTraffic(20, 20);

        List<LocationCandidateItem> candidates = service.buildCandidates(cp, request,
                Map.of("avoidRain", true, "indoorPreferred", true, "summary", "rain"), "uuid");

        assertThat(candidates).isNotEmpty();
        assertThat(candidates.get(0).getName()).isEqualTo("Rain Museum");
        assertThat(candidates.get(0).getWeatherSuitability()).contains("Weather-friendly");
    }

    @Test
    void buildCandidates_extractsChineseStructuredNames() {
        TaskCheckpoint cp = checkpoint();
        PlanNextAttractionRequest request = request(480);
        when(ragService.queryChunks(anyString(), eq("Hangzhou"), eq(20))).thenReturn(List.of(
                """
                # 雨天文化路线
                推荐景点：浙江省博物馆（武林馆区）
                - 景点名称：杭州工艺美术博物馆 - 适合室内慢逛
                """
        ));
        mockGeo("浙江省博物馆", 30.27, 120.16);
        mockGeo("杭州工艺美术博物馆", 30.29, 120.13);
        mockTraffic(15, 18);

        List<LocationCandidateItem> candidates = service.buildCandidates(cp, request,
                Map.of("avoidRain", true, "summary", "rain"), "uuid");

        assertThat(candidates).extracting(LocationCandidateItem::getName)
                .containsExactly("浙江省博物馆", "杭州工艺美术博物馆");
    }

    @Test
    void buildCandidates_extractsNamesFromMarkdownTables() {
        TaskCheckpoint cp = checkpoint();
        PlanNextAttractionRequest request = request(480);
        when(ragService.queryChunks(anyString(), eq("Hangzhou"), eq(20))).thenReturn(List.of(
                """
                | 景点 | 特色 | 建议 |
                | --- | --- | --- |
                | 中国丝绸博物馆 | 室内展陈 | 雨天优先 |
                | 太子湾公园 | 户外花景 | 晴天优先 |
                """
        ));
        mockGeo("中国丝绸博物馆", 30.23, 120.14);
        mockGeo("太子湾公园", 30.24, 120.15);
        mockTraffic(10, 14);

        List<LocationCandidateItem> candidates = service.buildCandidates(cp, request,
                Map.of("avoidRain", true, "summary", "rain"), "uuid");

        assertThat(candidates).extracting(LocationCandidateItem::getName)
                .containsExactly("中国丝绸博物馆", "太子湾公园");
    }

    @Test
    void buildCandidates_emptyRagReturnsEmpty() {
        TaskCheckpoint cp = checkpoint();
        when(ragService.queryChunks(anyString(), eq("Hangzhou"), eq(20))).thenReturn(List.of());

        List<LocationCandidateItem> candidates = service.buildCandidates(cp, request(480), Map.of(), "uuid");

        assertThat(candidates).isEmpty();
    }

    private TaskCheckpoint checkpoint() {
        TaskCheckpoint cp = new TaskCheckpoint();
        cp.setRegion("Hangzhou");
        cp.setUserIntent("indoor culture");
        PlanningConfig config = new PlanningConfig();
        config.setTravelMode("driving");
        config.setDefaultVisitDurationMin(120);
        cp.setPlanningConfig(config);
        cp.setCompletedSteps(new ArrayList<>());
        cp.setCurrentStepIndex(0);
        return cp;
    }

    private PlanNextAttractionRequest request(int remainingMin) {
        PlanNextAttractionRequest request = new PlanNextAttractionRequest();
        request.setRegion("Hangzhou");
        request.setCurrentPositionName("Hotel");
        request.setCurrentLat(30.25);
        request.setCurrentLng(120.14);
        request.setTravelMode("driving");
        request.setRemainingTimeBudgetMin(remainingMin);
        request.setVisitedPoiNames(List.of());
        return request;
    }

    private void mockGeo(String name, double lat, double lng) {
        when(geocodeTool.execute(eq(Map.of("name", name, "region", "Hangzhou")), anyString()))
                .thenReturn(Map.of("lat", lat, "lng", lng, "adcode", "330100"));
    }

    private void mockTraffic(int... durations) {
        org.mockito.stubbing.OngoingStubbing<Map<String, Object>> stubbing =
                when(trafficTimeTool.execute(any(), anyString()));
        for (int duration : durations) {
            stubbing = stubbing.thenReturn(Map.of("durationMin", duration));
        }
    }
}
