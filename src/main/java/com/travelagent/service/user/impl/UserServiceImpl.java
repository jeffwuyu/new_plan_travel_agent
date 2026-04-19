package com.travelagent.service.user.impl;

import com.travelagent.exception.BusinessException;
import com.travelagent.mapper.UserMapper;
import com.travelagent.model.dto.LoginRequest;
import com.travelagent.model.dto.LoginResponse;
import com.travelagent.model.dto.RegisterRequest;
import com.travelagent.model.entity.User;
import com.travelagent.service.user.UserService;
import com.travelagent.util.JwtUtil;
import com.travelagent.util.RedisUtil;
import org.mindrot.jbcrypt.BCrypt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;

/**
 * 中文注释：服务实现类，负责承载 User Service Impl 对应的核心业务逻辑。
 */

@Service
public class UserServiceImpl implements UserService {

    private static final Logger log = LoggerFactory.getLogger(UserServiceImpl.class);

    private static final String JWT_BLACKLIST_PREFIX = "jwt:blacklist:";

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private JwtUtil jwtUtil;

    @Autowired
    private RedisUtil redisUtil;

    @Override
    @Transactional
    public User register(RegisterRequest request) {
        if (userMapper.existsByEmail(request.getEmail())) {
            throw new BusinessException(400, "邮箱已被注册");
        }
        if (userMapper.existsByUsername(request.getUsername())) {
            throw new BusinessException(400, "用户名已被占用");
        }

        User user = new User();
        user.setUsername(request.getUsername());
        user.setEmail(request.getEmail());
        user.setPasswordHash(BCrypt.hashpw(request.getPassword(), BCrypt.gensalt()));
        user.setUserLevel(1); // Default: REGULAR
        user.setStatus(1);

        userMapper.insert(user);
        log.info("New user registered: id={}, email={}", user.getId(), user.getEmail());
        user.setPasswordHash(null); // Never expose hash
        return user;
    }

    @Override
    public LoginResponse login(LoginRequest request) {
        User user = userMapper.findByEmail(request.getEmail());
        if (user == null || !user.isActive()) {
            throw new BusinessException(401, "邮箱或密码错误");
        }
        if (!BCrypt.checkpw(request.getPassword(), user.getPasswordHash())) {
            throw new BusinessException(401, "邮箱或密码错误");
        }

        String token = jwtUtil.generateToken(user.getId(), user.getUserLevel());
        long expiresAt = jwtUtil.getExpirationFromToken(token).getTime();

        log.info("User logged in: id={}", user.getId());
        return new LoginResponse(token, user.getId(), user.getUsername(),
                                 user.getUserLevel(), expiresAt);
    }

    @Override
    public void logout(String token) {
        if (token == null || token.isBlank()) return;
        long remainingMs = jwtUtil.getRemainingValidityMs(token);
        if (remainingMs > 0) {
            // Blacklist the token for the remainder of its validity
            redisUtil.set(JWT_BLACKLIST_PREFIX + token, "1",
                Duration.ofMillis(remainingMs + 1000));
        }
        log.info("Token blacklisted (logout), remaining validity: {}ms", remainingMs);
    }

    @Override
    @Transactional
    public void cancelAccount(Long userId) {
        User user = userMapper.findById(userId);
        if (user == null) {
            throw new BusinessException(404, "用户不存在");
        }
        userMapper.softDelete(userId);
        log.info("Account cancelled (soft-deleted): userId={}", userId);
    }

    @Override
    public User findById(Long userId) {
        User user = userMapper.findById(userId);
        if (user != null) {
            user.setPasswordHash(null);
        }
        return user;
    }

    @Override
    @Transactional
    public void updateUserLevel(Long userId, int newLevel) {
        User user = new User();
        user.setId(userId);
        user.setUserLevel(newLevel);
        userMapper.update(user);
        // Invalidate quota config cache for this user (level changed)
        log.info("User level updated: userId={}, newLevel={}", userId, newLevel);
    }
}
