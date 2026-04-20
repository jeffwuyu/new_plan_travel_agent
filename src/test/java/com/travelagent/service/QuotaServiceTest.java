package com.travelagent.service;

import com.travelagent.exception.QuotaExhaustedException;
import com.travelagent.mapper.UserMapper;
import com.travelagent.mapper.UserQuotaConfigMapper;
import com.travelagent.mapper.UserQuotaUsageMapper;
import com.travelagent.model.entity.UserQuotaConfig;
import com.travelagent.model.entity.UserQuotaUsage;
import com.travelagent.service.user.impl.QuotaServiceImpl;
import com.travelagent.util.RedisUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for QuotaServiceImpl.
 */

/**
 * 中文注释：测试类，用于验证 Quota Service Test 相关行为是否符合预期。
 */

@ExtendWith(MockitoExtension.class)
@DisplayName("QuotaService Tests")
class QuotaServiceTest {

    @Mock
    private RedisUtil redisUtil;

    @Mock
    private UserQuotaConfigMapper quotaConfigMapper;

    /** 新增：配额用量快照 Mapper（snapshotToDatabase 需要）。 */
    @Mock
    private UserQuotaUsageMapper quotaUsageMapper;

    /** 新增：用户 Mapper（snapshotAllActiveUsers 需要）。 */
    @Mock
    private UserMapper userMapper;

    @InjectMocks
    private QuotaServiceImpl quotaService;

    private UserQuotaConfig regularConfig;

    @BeforeEach
    void setUp() {
        regularConfig = new UserQuotaConfig();
        regularConfig.setUserLevel(1);
        regularConfig.setDailyTokenLimit(10000);
        regularConfig.setMonthlyTokenLimit(100000);
        regularConfig.setMaxConcurrentTasks(2);
        regularConfig.setMaxPlanSteps(15);
    }

    @Test
    @DisplayName("getQuotaConfig - 从数据库加载并缓存到 Redis")
    void getQuotaConfig_loadsFromDb() {
        when(redisUtil.get(anyString())).thenReturn(null);
        when(quotaConfigMapper.findByUserLevel(1)).thenReturn(regularConfig);

        UserQuotaConfig result = quotaService.getQuotaConfig(1);

        assertNotNull(result);
        assertEquals(10000, result.getDailyTokenLimit());
        verify(redisUtil).set(anyString(), eq(regularConfig), any());
    }

    @Test
    @DisplayName("getQuotaConfig - 命中 Redis 缓存时不查数据库")
    void getQuotaConfig_usesRedisCache() {
        when(redisUtil.get(anyString())).thenReturn(regularConfig);

        UserQuotaConfig result = quotaService.getQuotaConfig(1);

        assertNotNull(result);
        verify(quotaConfigMapper, never()).findByUserLevel(anyInt());
    }

    @Test
    @DisplayName("checkDailyQuota - 配额充足时不抛出异常")
    void checkDailyQuota_sufficientQuota_noException() {
        when(redisUtil.get(anyString())).thenReturn(regularConfig);
        when(redisUtil.checkQuota(anyString(), eq(10000L))).thenReturn(true);

        assertDoesNotThrow(() -> quotaService.checkDailyQuota(1L, 1));
    }

    @Test
    @DisplayName("checkDailyQuota - 配额耗尽时抛出 QuotaExhaustedException")
    void checkDailyQuota_exhausted_throws() {
        when(redisUtil.get(anyString())).thenReturn(regularConfig);
        when(redisUtil.checkQuota(anyString(), eq(10000L))).thenReturn(false);

        QuotaExhaustedException ex = assertThrows(QuotaExhaustedException.class,
            () -> quotaService.checkDailyQuota(1L, 1));

        assertEquals("daily", ex.getPeriodType());
        assertEquals(429, ex.getHttpStatus());
    }

    @Test
    @DisplayName("debitTokens - 扣减成功返回新累计值")
    void debitTokens_success() {
        when(redisUtil.get(anyString())).thenReturn(regularConfig);
        when(redisUtil.incrementWithTtl(contains(":daily:"), eq(500L), any()))
            .thenReturn(500L);
        when(redisUtil.incrementWithTtl(contains(":monthly:"), eq(500L), any()))
            .thenReturn(500L);

        long newTotal = quotaService.debitTokens(1L, 1, 500);

        assertEquals(500L, newTotal);
    }

    @Test
    @DisplayName("debitTokens - 扣减后超出日限额抛出 QuotaExhaustedException")
    void debitTokens_exceedsLimit_throws() {
        when(redisUtil.get(anyString())).thenReturn(regularConfig);
        // Simulates cumulative usage exceeding the 10000 limit
        when(redisUtil.incrementWithTtl(contains(":daily:"), anyLong(), any()))
            .thenReturn(10001L);
        when(redisUtil.incrementWithTtl(contains(":monthly:"), anyLong(), any()))
            .thenReturn(10001L);

        assertThrows(QuotaExhaustedException.class,
            () -> quotaService.debitTokens(1L, 1, 100));
    }

    @Test
    @DisplayName("getDailyUsage - 从 Redis 读取当日用量")
    void getDailyUsage_readsFromRedis() {
        when(redisUtil.getString(anyString())).thenReturn("3500");

        long usage = quotaService.getDailyUsage(1L);

        assertEquals(3500L, usage);
    }

    @Test
    @DisplayName("getDailyUsage - Redis 无数据返回 0")
    void getDailyUsage_noData_returnsZero() {
        when(redisUtil.getString(anyString())).thenReturn(null);

        long usage = quotaService.getDailyUsage(1L);

        assertEquals(0L, usage);
    }

    // ===================== snapshotToDatabase =====================

    /**
     * 测试 snapshotToDatabase：正常路径。
     *
     * 场景：Redis 中存有 daily=3500、monthly=20000 的用量数据，
     * 调用快照后应执行两次 quotaUsageMapper.upsert（daily 和 monthly 各一次）。
     *
     * 验证点：
     *   1. upsert 被调用 2 次
     *   2. daily 快照的 tokensUsed=3500
     *   3. monthly 快照的 tokensUsed=20000
     */
    @Test
    @DisplayName("snapshotToDatabase - 正常快照 daily 和 monthly 到数据库")
    void snapshotToDatabase_success_upsertsDaily_and_Monthly() {
        // Redis 返回 daily=3500，monthly=20000
        when(redisUtil.getString(contains(":daily:"))).thenReturn("3500");
        when(redisUtil.getString(contains(":monthly:"))).thenReturn("20000");
        when(quotaUsageMapper.upsert(any(UserQuotaUsage.class))).thenReturn(1);

        assertDoesNotThrow(() -> quotaService.snapshotToDatabase(1L));

        // 验证 upsert 被调用了两次（daily + monthly）
        verify(quotaUsageMapper, times(2)).upsert(any(UserQuotaUsage.class));

        // 验证 daily 快照携带正确的 tokensUsed
        verify(quotaUsageMapper).upsert(argThat(u ->
                "daily".equals(u.getPeriodType()) && u.getTokensUsed() == 3500));

        // 验证 monthly 快照携带正确的 tokensUsed
        verify(quotaUsageMapper).upsert(argThat(u ->
                "monthly".equals(u.getPeriodType()) && u.getTokensUsed() == 20000));
    }

    @Test
    @DisplayName("snapshotToDatabase - Redis 无用量数据时快照为 0")
    void snapshotToDatabase_noRedisData_snapshots_zero() {
        // Redis 中无数据（用户从未调用过 LLM）
        when(redisUtil.getString(anyString())).thenReturn(null);
        when(quotaUsageMapper.upsert(any(UserQuotaUsage.class))).thenReturn(1);

        assertDoesNotThrow(() -> quotaService.snapshotToDatabase(1L));

        verify(quotaUsageMapper).upsert(argThat(u ->
                "daily".equals(u.getPeriodType()) && u.getTokensUsed() == 0));
        verify(quotaUsageMapper).upsert(argThat(u ->
                "monthly".equals(u.getPeriodType()) && u.getTokensUsed() == 0));
    }

    @Test
    @DisplayName("snapshotToDatabase - upsert 抛出异常时被静默捕获，不向外抛出")
    void snapshotToDatabase_dbError_isSilentlyCaught() {
        // 模拟数据库写入失败（如网络抖动）
        when(redisUtil.getString(anyString())).thenReturn("100");
        when(quotaUsageMapper.upsert(any(UserQuotaUsage.class)))
                .thenThrow(new RuntimeException("DB connection lost"));

        // 快照失败不应影响主流程，即不向外抛出任何异常
        assertDoesNotThrow(() -> quotaService.snapshotToDatabase(1L));
    }

    // ===================== snapshotAllActiveUsers =====================

    /**
     * 测试 snapshotAllActiveUsers：批量快照所有活跃用户。
     *
     * 场景：DB 中有 3 个活跃用户（ID: 1, 2, 3），
     * 调用批量快照后应对每个用户各调用一次 snapshotToDatabase（即 upsert 调用 6 次）。
     */
    @Test
    @DisplayName("snapshotAllActiveUsers - 对所有活跃用户各执行一次 snapshotToDatabase")
    void snapshotAllActiveUsers_snapshotsAllUsers() {
        when(userMapper.findAllActiveIds()).thenReturn(List.of(1L, 2L, 3L));
        when(redisUtil.getString(anyString())).thenReturn("500");
        when(quotaUsageMapper.upsert(any(UserQuotaUsage.class))).thenReturn(1);

        assertDoesNotThrow(() -> quotaService.snapshotAllActiveUsers());

        // 3 用户 × 2 周期（daily + monthly）= 6 次 upsert
        verify(quotaUsageMapper, times(6)).upsert(any(UserQuotaUsage.class));
    }

    @Test
    @DisplayName("snapshotAllActiveUsers - 无活跃用户时不执行任何快照")
    void snapshotAllActiveUsers_noActiveUsers_noUpsert() {
        when(userMapper.findAllActiveIds()).thenReturn(List.of());

        assertDoesNotThrow(() -> quotaService.snapshotAllActiveUsers());

        verify(quotaUsageMapper, never()).upsert(any(UserQuotaUsage.class));
    }

    @Test
    @DisplayName("snapshotAllActiveUsers - 某用户快照失败不阻断其他用户")
    void snapshotAllActiveUsers_partialFailure_continuesOtherUsers() {
        when(userMapper.findAllActiveIds()).thenReturn(List.of(1L, 2L));
        // 用户 1 的 daily Redis 读取正常，monthly 抛异常模拟第一用户快照部分失败
        when(redisUtil.getString(contains("user:1:daily"))).thenReturn("100");
        when(redisUtil.getString(contains("user:2:daily"))).thenReturn("300");
        when(redisUtil.getString(contains("user:2:monthly"))).thenReturn("400");
        // 用户 1 的 upsert 抛出异常（模拟 DB 超时）
        when(quotaUsageMapper.upsert(argThat(u -> u != null && u.getUserId() == 1L)))
                .thenThrow(new RuntimeException("timeout"));
        when(quotaUsageMapper.upsert(argThat(u -> u != null && u.getUserId() == 2L)))
                .thenReturn(1);

        // 整体不应抛出异常（用户 1 失败不影响用户 2）
        assertDoesNotThrow(() -> quotaService.snapshotAllActiveUsers());
    }
}
