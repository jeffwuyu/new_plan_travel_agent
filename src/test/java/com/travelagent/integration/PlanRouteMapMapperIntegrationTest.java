package com.travelagent.integration;

import com.travelagent.mapper.PlanRouteMapMapper;
import com.travelagent.mapper.PlanRouteMapJobMapper;
import com.travelagent.model.entity.PlanDayRouteMap;
import com.travelagent.model.entity.PlanRouteMapJob;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

@DisplayName("Plan route map mapper integration tests")
class PlanRouteMapMapperIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private PlanRouteMapMapper routeMapMapper;

    @Autowired
    private PlanRouteMapJobMapper routeMapJobMapper;

    @Test
    void countCreatedByUserBetween_countsOnlyMatchingUserAndWindow() {
        routeMapMapper.insert(routeMap(88L, 7L, 1, "anime_travel_map"));
        routeMapMapper.insert(routeMap(88L, 8L, 2, "anime_travel_map"));
        routeMapMapper.insert(routeMap(89L, 7L, 1, "watercolor_travel_map"));

        LocalDateTime start = LocalDate.now().atStartOfDay();
        LocalDateTime end = LocalDate.now().plusDays(1).atStartOfDay();

        assertEquals(2, routeMapMapper.countCreatedByUserBetween(7L, start, end));
        assertEquals(1, routeMapMapper.countCreatedByUserBetween(8L, start, end));
        assertEquals(0, routeMapMapper.countCreatedByUserBetween(7L, start.minusDays(2), start.minusDays(1)));
    }

    @Test
    void findFailedDispatchBefore_returnsOnlyDispatchFailures() {
        PlanDayRouteMap dispatchFailed = routeMap(88L, 7L, 1, "anime_travel_map");
        dispatchFailed.setStatus("failed");
        dispatchFailed.setErrorCode("ROUTE_MAP_DISPATCH_FAILED");
        routeMapMapper.insert(dispatchFailed);

        PlanDayRouteMap bailianFailed = routeMap(88L, 7L, 2, "anime_travel_map");
        bailianFailed.setStatus("failed");
        bailianFailed.setErrorCode("BAILIAN_TIMEOUT");
        routeMapMapper.insert(bailianFailed);

        PlanDayRouteMap pending = routeMap(88L, 7L, 3, "anime_travel_map");
        pending.setStatus("pending");
        pending.setErrorCode("ROUTE_MAP_DISPATCH_FAILED");
        routeMapMapper.insert(pending);

        List<PlanDayRouteMap> result = routeMapMapper.findFailedDispatchBefore(LocalDateTime.now().plusMinutes(1), 10);

        assertEquals(1, result.size());
        assertEquals(dispatchFailed.getId(), result.get(0).getId());
    }

    @Test
    void statisticsQueries_returnRouteMapAggregates() {
        PlanDayRouteMap succeeded = routeMap(88L, 7L, 1, "anime_travel_map");
        routeMapMapper.insert(succeeded);
        routeMapMapper.updateCompleted(succeeded.getId(), "succeeded", 100,
                "raw-1.png", "final-1.png", null, null, 1200L);

        PlanDayRouteMap failed = routeMap(88L, 7L, 2, "anime_travel_map");
        failed.setManualRegenCount(1);
        routeMapMapper.insert(failed);
        routeMapMapper.updateCompleted(failed.getId(), "failed", 80,
                null, null, "BAILIAN_TIMEOUT", "timeout", 800L);

        PlanDayRouteMap pending = routeMap(88L, 8L, 3, "anime_travel_map");
        routeMapMapper.insert(pending);

        LocalDateTime start = LocalDate.now().atStartOfDay();
        LocalDateTime end = LocalDate.now().plusDays(1).atStartOfDay();

        Map<String, Object> summary = routeMapMapper.summarizeBetween(start, end);
        assertEquals(3L, numberValue(summary, "totalCount"));
        assertEquals(1L, numberValue(summary, "succeededCount"));
        assertEquals(1L, numberValue(summary, "failedCount"));
        assertEquals(1L, numberValue(summary, "runningCount"));

        List<Map<String, Object>> failureCodes = routeMapMapper.countFailuresByErrorCodeBetween(start, end, 10);
        assertEquals("BAILIAN_TIMEOUT", value(failureCodes.get(0), "errorCode"));
        assertEquals(1L, numberValue(failureCodes.get(0), "count"));

        List<Map<String, Object>> topUsers = routeMapMapper.countByUserBetween(start, end, 10);
        assertEquals(7L, numberValue(topUsers.get(0), "userId"));
        assertEquals(2L, numberValue(topUsers.get(0), "count"));
    }

    @Test
    void routeMapJobMapper_tracksPersistentWorkerJobLifecycle() {
        PlanDayRouteMap routeMap = routeMap(88L, 7L, 1, "anime_travel_map");
        routeMapMapper.insert(routeMap);

        PlanRouteMapJob job = new PlanRouteMapJob();
        job.setRouteMapId(routeMap.getId());
        job.setPlanId(routeMap.getPlanId());
        job.setUserId(routeMap.getUserId());
        job.setDayNumber(routeMap.getDayNumber());
        job.setStyle(routeMap.getStyle());
        job.setStatus("queued");
        job.setTriggerType("test");
        job.setAttempts(0);
        job.setMaxAttempts(1);
        job.setAvailableAt(LocalDateTime.now().minusSeconds(1));
        routeMapJobMapper.insert(job);

        assertEquals(1, routeMapJobMapper.findDispatchable(LocalDateTime.now(), 10).size());
        assertEquals(1, routeMapJobMapper.markRunning(job.getId(), "worker-a", LocalDateTime.now().plusMinutes(5)));

        PlanRouteMapJob running = routeMapJobMapper.findById(job.getId());
        assertEquals("running", running.getStatus());
        assertEquals(1, running.getAttempts());

        routeMapJobMapper.markFailed(job.getId(), "dead_letter", "ROUTE_MAP_DISPATCH_FAILED",
                "executor rejected", LocalDateTime.now());
        PlanRouteMapJob deadLetter = routeMapJobMapper.findById(job.getId());
        assertEquals("dead_letter", deadLetter.getStatus());
        assertEquals("ROUTE_MAP_DISPATCH_FAILED", deadLetter.getErrorCode());
    }

    private PlanDayRouteMap routeMap(Long planId, Long userId, int dayNumber, String style) {
        PlanDayRouteMap routeMap = new PlanDayRouteMap();
        routeMap.setPlanId(planId);
        routeMap.setUserId(userId);
        routeMap.setDayNumber(dayNumber);
        routeMap.setStyle(style);
        routeMap.setStatus("pending");
        routeMap.setProgressPercent(0);
        routeMap.setRetryCount(0);
        routeMap.setManualRegenCount(0);
        return routeMap;
    }

    private Object value(Map<String, Object> row, String key) {
        for (Map.Entry<String, Object> entry : row.entrySet()) {
            if (key.replace("_", "").equalsIgnoreCase(entry.getKey().replace("_", ""))) {
                return entry.getValue();
            }
        }
        return null;
    }

    private long numberValue(Map<String, Object> row, String key) {
        Object value = value(row, key);
        return value instanceof Number number ? number.longValue() : 0L;
    }
}
