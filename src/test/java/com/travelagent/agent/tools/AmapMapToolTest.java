package com.travelagent.agent.tools;

import com.travelagent.client.amap.AmapClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("AmapMapTool Tests")
class AmapMapToolTest {

    @Mock private AmapClient amapClient;

    @InjectMocks
    private AmapMapTool amapMapTool;

    @Test
    @DisplayName("execute returns POI, route, commute and nearby analysis for validators")
    @SuppressWarnings("unchecked")
    void execute_buildsMapAnalysis() {
        when(amapClient.searchPois(eq("西湖"), eq("杭州"), eq(""), eq(1), eq(10)))
                .thenReturn(List.of(Map.of(
                        "name", "西湖风景名胜区",
                        "lat", 30.259041,
                        "lng", 120.141706,
                        "address", "龙井路1号")));
        when(amapClient.searchPois(eq("灵隐寺"), eq("杭州"), eq(""), eq(1), eq(10)))
                .thenReturn(List.of(Map.of(
                        "name", "灵隐寺",
                        "lat", 30.240826,
                        "lng", 120.102681,
                        "address", "法云弄1号")));
        when(amapClient.getTravelDuration(
                eq(120.141706), eq(30.259041), eq(120.102681), eq(30.240826), eq("transit")))
                .thenReturn(Map.of("durationMin", 31, "distanceMeters", 6200, "routeMode", "transit"));
        when(amapClient.getTravelDuration(
                eq(120.170000), eq(30.250000), anyDouble(), anyDouble(), eq("transit")))
                .thenReturn(Map.of("durationMin", 24, "distanceMeters", 3800, "routeMode", "transit"));
        when(amapClient.getTravelDuration(
                eq(120.090000), eq(30.300000), anyDouble(), anyDouble(), eq("transit")))
                .thenReturn(Map.of("durationMin", 72, "distanceMeters", 21000, "routeMode", "transit"));
        when(amapClient.searchNearbyPois(anyDouble(), anyDouble(), anyInt(), anyString(), anyString(), eq(1), eq(5)))
                .thenReturn(List.of(Map.of(
                        "name", "龙翔桥地铁站",
                        "distanceMeters", 820,
                        "lat", 30.258617,
                        "lng", 120.165122)));

        Map<String, Object> result = amapMapTool.execute(Map.of(
                "city", "杭州",
                "travelMode", "transit",
                "poiKeywords", List.of("西湖", "灵隐寺"),
                "accommodations", List.of(
                        Map.of("name", "湖滨商圈", "lat", 30.250000, "lng", 120.170000),
                        Map.of("name", "城西区域", "lat", 30.300000, "lng", 120.090000))
        ), "task-uuid-step2-amap_map");

        assertThat(result.get("source")).isEqualTo("amap:map");
        assertThat((List<Map<String, Object>>) result.get("poiResults")).hasSize(2);
        List<Map<String, Object>> routes = (List<Map<String, Object>>) result.get("routeMatrix");
        assertThat(routes).hasSize(1);
        assertThat(routes.get(0).get("onTheWay")).isEqualTo(true);

        List<Map<String, Object>> commutes = (List<Map<String, Object>>) result.get("accommodationCommutes");
        assertThat(commutes).hasSize(2);
        assertThat(commutes.get(0).get("name")).isEqualTo("湖滨商圈");

        Map<String, Object> routeEfficiency = (Map<String, Object>) result.get("routeEfficiency");
        assertThat(routeEfficiency.get("allPairsConvenient")).isEqualTo(true);
        assertThat((List<String>) result.get("validatorHints")).contains("accommodation_commute_score_low");
        assertThat((List<Map<String, Object>>) result.get("nearbyTransit")).hasSize(2);
        assertThat((List<Map<String, Object>>) result.get("nearbyAmenities")).hasSize(2);
    }
}
