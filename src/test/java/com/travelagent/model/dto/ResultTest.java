package com.travelagent.model.dto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the unified Result<T> response wrapper.
 */

/**
 * 中文注释：测试类，用于验证 Result Test 相关行为是否符合预期。
 */

@DisplayName("Result<T> Tests")
class ResultTest {

    @Test
    @DisplayName("Result.success(data) - code=200, data 正确")
    void success_withData() {
        Result<String> result = Result.success("hello");
        assertEquals(200, result.getCode());
        assertEquals("hello", result.getData());
        assertTrue(result.isSuccess());
    }

    @Test
    @DisplayName("Result.success() - code=200, data=null")
    void success_noData() {
        Result<Void> result = Result.success();
        assertEquals(200, result.getCode());
        assertNull(result.getData());
        assertTrue(result.isSuccess());
    }

    @Test
    @DisplayName("Result.error - code 和 message 正确")
    void error() {
        Result<Void> result = Result.error(500, "Server Error");
        assertEquals(500, result.getCode());
        assertEquals("Server Error", result.getMessage());
        assertFalse(result.isSuccess());
    }

    @Test
    @DisplayName("Result.badRequest - code=400")
    void badRequest() {
        Result<Void> result = Result.badRequest("参数错误");
        assertEquals(400, result.getCode());
    }

    @Test
    @DisplayName("Result.unauthorized - code=401")
    void unauthorized() {
        Result<Void> result = Result.unauthorized("未授权");
        assertEquals(401, result.getCode());
    }

    @Test
    @DisplayName("Result.tooManyRequests - code=429")
    void tooManyRequests() {
        Result<Void> result = Result.tooManyRequests("请求过于频繁");
        assertEquals(429, result.getCode());
    }

    @Test
    @DisplayName("Result.notFound - code=404")
    void notFound() {
        Result<Void> result = Result.notFound("资源不存在");
        assertEquals(404, result.getCode());
    }
}
