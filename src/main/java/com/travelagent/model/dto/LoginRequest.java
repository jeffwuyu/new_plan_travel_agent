package com.travelagent.model.dto;

import lombok.Data;

import jakarta.validation.constraints.NotBlank;

/**
 * 中文注释：DTO 类，用于在接口或服务之间传递 Login Request 数据。
 */

@Data
public class LoginRequest {

    @NotBlank(message = "邮箱不能为空")
    private String email;

    @NotBlank(message = "密码不能为空")
    private String password;
}
