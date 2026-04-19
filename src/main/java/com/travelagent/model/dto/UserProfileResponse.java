package com.travelagent.model.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 中文注释：DTO 类，用于在接口或服务之间传递 User Profile Response 数据。
 */

@Data
public class UserProfileResponse {

    private Long id;
    private String username;
    private String email;
    private Integer userLevel;
    private String userLevelLabel;
    private LocalDateTime createdAt;
}
