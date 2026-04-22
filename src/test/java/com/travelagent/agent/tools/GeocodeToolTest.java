package com.travelagent.agent.tools;

import com.travelagent.agent.mcp.McpToolExecutionService;
import com.travelagent.client.amap.AmapClient;
import com.travelagent.config.CacheConfig;
import com.travelagent.mapper.AttractionMapper;
import com.travelagent.model.entity.Attraction;
import com.travelagent.service.cache.MultiLevelCacheService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 中文注释：测试类，用于验证 Geocode Tool Test 相关行为是否符合预期。
 */

@ExtendWith(MockitoExtension.class)
@DisplayName("GeocodeTool Tests")
class GeocodeToolTest {

    @Mock private AmapClient amapClient;
    @Mock private AttractionMapper attractionMapper;
    @Mock private MultiLevelCacheService cacheService;
    @Mock private McpToolExecutionService mcpToolExecutionService;

    @InjectMocks
    private GeocodeTool geocodeTool;

    private static final String IDEMPOTENCY_KEY = "task-uuid-step0-geocode";

    // -----------------------------------------------------------------------
    // getName contract
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("getName() returns 'geocode'")
    void getName_returnsCorrectName() {
        assertThat(geocodeTool.getName()).isEqualTo("geocode");
    }

    // -----------------------------------------------------------------------
    // DB cache hit — skip Amap call
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("DB cache hit: returns coordinates without calling Amap")
    @SuppressWarnings("unchecked")
    void execute_dbCacheHit_skipsAmapCall() {
        Attraction cachedAttr = new Attraction();
        cachedAttr.setName("兵马俑");
        cachedAttr.setRegion("西安市");
        cachedAttr.setLatitude(new BigDecimal("34.384232"));
        cachedAttr.setLongitude(new BigDecimal("109.278927"));
        cachedAttr.setCachedAt(LocalDateTime.now());

        when(cacheService.get(eq(CacheConfig.ATTRACTION_BASIC), eq("兵马俑:西安市"),
                eq(Attraction.class), any(Supplier.class))).thenReturn(cachedAttr);

        Map<String, Object> args = Map.of("name", "兵马俑", "region", "西安市");
        Map<String, Object> result = geocodeTool.execute(args, IDEMPOTENCY_KEY);

        assertThat(((Number) result.get("lat")).doubleValue()).isCloseTo(34.384232, within(0.0001));
        assertThat(((Number) result.get("lng")).doubleValue()).isCloseTo(109.278927, within(0.0001));
        verifyNoInteractions(amapClient);
        verify(attractionMapper, never()).insert(any());
    }

    // -----------------------------------------------------------------------
    // Full cache miss — call Amap and persist to DB
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("Cache miss: calls Amap, persists to DB, backfills cache")
    @SuppressWarnings("unchecked")
    void execute_cacheMiss_callsAmapAndPersists() {
        when(cacheService.get(eq(CacheConfig.ATTRACTION_BASIC), eq("兵马俑:西安市"),
                eq(Attraction.class), any(Supplier.class))).thenReturn(null);

        Map<String, Object> amapResult = Map.of("lat", 34.384232, "lng", 109.278927, "adcode", "610100");
        when(amapClient.geocode("兵马俑", "西安市")).thenReturn(amapResult);

        Map<String, Object> args = Map.of("name", "兵马俑", "region", "西安市");
        Map<String, Object> result = geocodeTool.execute(args, IDEMPOTENCY_KEY);

        assertThat(((Number) result.get("lat")).doubleValue()).isCloseTo(34.384232, within(0.0001));

        // Verify DB insert
        ArgumentCaptor<Attraction> attrCaptor = ArgumentCaptor.forClass(Attraction.class);
        verify(attractionMapper).insert(attrCaptor.capture());
        Attraction inserted = attrCaptor.getValue();
        assertThat(inserted.getName()).isEqualTo("兵马俑");
        assertThat(inserted.getRegion()).isEqualTo("西安市");
        assertThat(inserted.getLatitude().doubleValue()).isCloseTo(34.384232, within(0.0001));

        // Verify cache backfill
        verify(cacheService).put(eq(CacheConfig.ATTRACTION_BASIC), eq("兵马俑:西安市"), any(), any());
    }

    // -----------------------------------------------------------------------
    // DB insert failure is non-fatal
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("DB insert failure is non-fatal: result still returned")
    @SuppressWarnings("unchecked")
    void execute_dbInsertFailure_resultStillReturned() {
        when(cacheService.get(any(), any(), any(), any(Supplier.class))).thenReturn(null);
        when(amapClient.geocode(any(), any())).thenReturn(Map.of("lat", 34.38, "lng", 109.28, "adcode", ""));
        doThrow(new RuntimeException("DB connection error")).when(attractionMapper).insert(any());

        Map<String, Object> args = Map.of("name", "兵马俑", "region", "西安市");
        // Should not throw
        assertThatNoException().isThrownBy(() -> geocodeTool.execute(args, IDEMPOTENCY_KEY));
    }
    @Test
    @DisplayName("Cache miss with MCP enabled: uses MCP result before REST fallback")
    @SuppressWarnings("unchecked")
    void execute_cacheMiss_prefersMcp() {
        when(cacheService.get(any(), any(), any(), any(Supplier.class))).thenReturn(null);
        when(mcpToolExecutionService.isEnabled()).thenReturn(true);
        when(mcpToolExecutionService.execute(eq("geocode"), any()))
                .thenReturn(Map.of("lat", 30.274084, "lng", 120.15507, "adcode", "330106"));

        Map<String, Object> result = geocodeTool.execute(Map.of("name", "西湖", "region", "杭州"), IDEMPOTENCY_KEY);

        assertThat(result.get("adcode")).isEqualTo("330106");
        verifyNoInteractions(amapClient);
    }

    @Test
    @DisplayName("MCP failure falls back to REST geocode")
    @SuppressWarnings("unchecked")
    void execute_mcpFailure_fallsBackToRest() {
        when(cacheService.get(any(), any(), any(), any(Supplier.class))).thenReturn(null);
        when(mcpToolExecutionService.isEnabled()).thenReturn(true);
        when(mcpToolExecutionService.execute(eq("geocode"), any()))
                .thenThrow(new RuntimeException("mcp unavailable"));
        when(amapClient.geocode("西湖", "杭州"))
                .thenReturn(Map.of("lat", 30.274084, "lng", 120.15507, "adcode", "330106"));

        Map<String, Object> result = geocodeTool.execute(Map.of("name", "西湖", "region", "杭州"), IDEMPOTENCY_KEY);

        assertThat(result.get("mcpFallback")).isEqualTo(true);
        verify(amapClient).geocode("西湖", "杭州");
    }
}
