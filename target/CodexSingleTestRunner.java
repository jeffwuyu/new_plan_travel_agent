package com.travelagent.service.cache;

import org.mockito.MockitoAnnotations;

public class CodexSingleTestRunner {
    public static void main(String[] args) throws Exception {
        run("get_l1Hit_returnsValueWithoutRedisOrLoader");
        run("get_l2Hit_backfillsL1AndSkipsLoader");
        run("get_l3Hit_writesThroughToL1AndL2");
        run("get_allMiss_returnsNull");
        run("get_redisFailure_degradesToLoader");
        run("put_writesToL1AndL2");
        run("evict_removesFromL1AndL2");
        run("get_attractionBasic_usesOneHourTtl");
    }

    private static void run(String methodName) throws Exception {
        MultiLevelCacheServiceImplTest test = new MultiLevelCacheServiceImplTest();
        try (AutoCloseable mocks = MockitoAnnotations.openMocks(test)) {
            test.setUp();
            MultiLevelCacheServiceImplTest.class.getDeclaredMethod(methodName).invoke(test);
            System.out.println("passed=" + methodName);
        } catch (Throwable throwable) {
            System.out.println("failed=" + methodName);
            Throwable cause = throwable.getCause() != null ? throwable.getCause() : throwable;
            cause.printStackTrace(System.out);
            System.exit(1);
        }
    }
}
