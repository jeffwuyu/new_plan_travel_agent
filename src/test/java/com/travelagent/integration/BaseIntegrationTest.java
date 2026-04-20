package com.travelagent.integration;

import com.github.pagehelper.PageHelper;
import com.travelagent.agent.planner.TaskDispatcher;
import com.travelagent.client.amap.AmapClient;
import com.travelagent.client.dashscope.DashscopeLlmClient;
import com.travelagent.client.dashvector.DashVectorClient;
import com.travelagent.client.oss.OssClient;
import com.travelagent.model.entity.UserQuotaConfig;
import com.travelagent.service.user.QuotaService;
import org.junit.jupiter.api.BeforeEach;
import org.mockito.Mockito;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.jdbc.Sql;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;

/**
 * Base class for all integration tests.
 *
 * Provides:
 * - H2 in-memory database via application-test.yml
 * - Redis infrastructure replaced by @MockitoBean (no real connection)
 * - All external API clients replaced by @MockitoBean (no real HTTP calls)
 * - QuotaService mocked to avoid Redis Lua script execution in test context
 * - Schema/data reset before each test method via @Sql
 *
 * Spring caches the application context when @MockitoBean declarations are identical,
 * so all subclasses share one context instance.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Sql(scripts = {"/schema-test.sql", "/data-test.sql"},
    executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
public abstract class BaseIntegrationTest {

    // Redis: replace all infrastructure beans to prevent real connections
    @MockitoBean
    protected RedisConnectionFactory redisConnectionFactory;

    @MockitoBean
    protected RedisTemplate<String, Object> redisTemplate;

    @MockitoBean
    protected StringRedisTemplate stringRedisTemplate;

    // QuotaService: mocked to avoid Redis Lua script execution (quota counting tested separately)
    @MockitoBean
    protected QuotaService quotaService;

    // TaskDispatcher: mocked to prevent @Scheduled polling from running during tests
    @MockitoBean
    protected TaskDispatcher taskDispatcher;

    // External API clients: replaced so no real HTTP calls are made
    @MockitoBean
    protected DashscopeLlmClient dashscopeLlmClient;

    @MockitoBean
    protected AmapClient amapClient;

    @MockitoBean
    protected OssClient ossClient;

    @MockitoBean
    protected DashVectorClient dashVectorClient;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUpMocks() {
        // PageHelper: clear any dirty thread-local state left by unit tests that mock
        // mapper beans (mocked mappers bypass MyBatis Executor, so PageHelper.startPage()
        // set by a controller under test never gets consumed by the interceptor).
        PageHelper.clearPage();

        // QuotaService: return permissive config so createTask doesn't NPE on getQuotaConfig
        UserQuotaConfig permissive = new UserQuotaConfig();
        permissive.setDailyTokenLimit(999999);
        permissive.setMonthlyTokenLimit(9999999);
        permissive.setMaxConcurrentTasks(10);
        permissive.setMaxPlanSteps(50);
        Mockito.lenient().when(quotaService.getQuotaConfig(anyInt())).thenReturn(permissive);

        // RedisTemplate: stub opsForValue() to return a no-op mock so RedisUtil.set() doesn't NPE
        // (used by UserServiceImpl.logout for JWT blacklisting)
        ValueOperations<String, Object> valueOps = mock(ValueOperations.class);
        Mockito.lenient().when(redisTemplate.opsForValue()).thenReturn(valueOps);

        // StringRedisTemplate: stub opsForValue() similarly (used by quota counters)
        ValueOperations<String, String> strValueOps = mock(ValueOperations.class);
        Mockito.lenient().when(stringRedisTemplate.opsForValue()).thenReturn(strValueOps);
    }
}
