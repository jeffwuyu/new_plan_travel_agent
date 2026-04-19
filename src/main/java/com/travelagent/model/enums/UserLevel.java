package com.travelagent.model.enums;

import lombok.Getter;

/**
 * User access levels. Determines token quotas, API call limits, and feature access.
 * Anonymous users (level=0) are conceptual only — they have no account row.
 */

/**
 * 中文注释：枚举枚举，用于声明 User Level 的可选取值集合。
 */

@Getter
public enum UserLevel {
    REGULAR(1, "普通用户"),
    VIP(2, "VIP用户"),
    ADMIN(3, "管理员");

    private final int code;
    private final String label;

    UserLevel(int code, String label) {
        this.code = code;
        this.label = label;
    }

    public static UserLevel fromCode(int code) {
        for (UserLevel level : values()) {
            if (level.code == code) return level;
        }
        throw new IllegalArgumentException("Unknown user level code: " + code);
    }
}
