package com.travelagent.service.user;

import com.travelagent.model.dto.LoginRequest;
import com.travelagent.model.dto.LoginResponse;
import com.travelagent.model.dto.RegisterRequest;
import com.travelagent.model.entity.User;

/**
 * User account operations: registration, login, logout, profile, cancellation.
 */

/**
 * 中文注释：服务接口，定义 User Service 相关业务能力。
 */

public interface UserService {

    /**
     * Register a new user. Returns the created User (without passwordHash).
     * @throws com.travelagent.exception.BusinessException if email/username taken
     */
    User register(RegisterRequest request);

    /**
     * Authenticate user and return a signed JWT token.
     * @throws com.travelagent.exception.BusinessException on invalid credentials
     */
    LoginResponse login(LoginRequest request);

    /**
     * Invalidate the given JWT by adding it to the Redis blacklist.
     * @param token the raw Bearer token (not the full header)
     */
    void logout(String token);

    /**
     * Soft-delete the user account (GDPR cancellation).
     * Also invalidates all active sessions by adding tokens to blacklist.
     */
    void cancelAccount(Long userId);

    /** Get user profile. Returns null if not found or deleted. */
    User findById(Long userId);

    /** Update user level (admin operation). */
    void updateUserLevel(Long userId, int newLevel);
}
