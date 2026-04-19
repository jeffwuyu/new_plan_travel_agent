package com.travelagent.model.enums;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for UserLevel, TaskStatus enums.
 */

/**
 * 中文注释：测试类，用于验证 Enums Test 相关行为是否符合预期。
 */

@DisplayName("Enums Tests")
class EnumsTest {

    @Test
    @DisplayName("UserLevel.fromCode - 正确映射每个等级")
    void userLevel_fromCode() {
        assertEquals(UserLevel.REGULAR, UserLevel.fromCode(1));
        assertEquals(UserLevel.VIP,     UserLevel.fromCode(2));
        assertEquals(UserLevel.ADMIN,   UserLevel.fromCode(3));
    }

    @Test
    @DisplayName("UserLevel.fromCode - 未知 code 抛出 IllegalArgumentException")
    void userLevel_unknownCode_throws() {
        assertThrows(IllegalArgumentException.class, () -> UserLevel.fromCode(99));
    }

    @Test
    @DisplayName("TaskStatus.isTerminal - 终态判断正确")
    void taskStatus_isTerminal() {
        assertTrue(TaskStatus.COMPLETED.isTerminal());
        assertTrue(TaskStatus.FAILED.isTerminal());
        assertTrue(TaskStatus.CANCELLED.isTerminal());
        assertFalse(TaskStatus.PLANNING.isTerminal());
        assertFalse(TaskStatus.PAUSED.isTerminal());
        assertFalse(TaskStatus.PENDING.isTerminal());
    }

    @Test
    @DisplayName("TaskStatus.isResumable - 只有 PAUSED 可续传")
    void taskStatus_isResumable() {
        assertTrue(TaskStatus.PAUSED.isResumable());
        assertFalse(TaskStatus.PLANNING.isResumable());
        assertFalse(TaskStatus.COMPLETED.isResumable());
    }

    @Test
    @DisplayName("TaskStatus.isActive - 活跃状态判断正确")
    void taskStatus_isActive() {
        assertTrue(TaskStatus.PLANNING.isActive());
        assertTrue(TaskStatus.TOOL_CALLING.isActive());
        assertTrue(TaskStatus.RESUMING.isActive());
        assertFalse(TaskStatus.PENDING.isActive());
        assertFalse(TaskStatus.PAUSED.isActive());
        assertFalse(TaskStatus.COMPLETED.isActive());
    }

    @Test
    @DisplayName("TaskStatus.fromCode - 正确映射状态字符串")
    void taskStatus_fromCode() {
        assertEquals(TaskStatus.PENDING,      TaskStatus.fromCode("pending"));
        assertEquals(TaskStatus.PLANNING,     TaskStatus.fromCode("planning"));
        assertEquals(TaskStatus.TOOL_CALLING, TaskStatus.fromCode("tool_calling"));
        assertEquals(TaskStatus.PAUSED,       TaskStatus.fromCode("paused"));
        assertEquals(TaskStatus.COMPLETED,    TaskStatus.fromCode("completed"));
        assertEquals(TaskStatus.FAILED,       TaskStatus.fromCode("failed"));
        assertEquals(TaskStatus.CANCELLED,    TaskStatus.fromCode("cancelled"));
    }

    @Test
    @DisplayName("TaskStatus.fromCode - 未知 code 抛出 IllegalArgumentException")
    void taskStatus_unknownCode_throws() {
        assertThrows(IllegalArgumentException.class, () -> TaskStatus.fromCode("unknown_state"));
    }
}
