package com.travelagent.model.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 中文注释：DTO 类，用于在接口或服务之间传递 Login Response 数据。
 */

@Data
@AllArgsConstructor
public class LoginResponse {

    private String token;
    private Long userId;
    private String username;
    private Integer userLevel;
    private Long expiresAt;
}
