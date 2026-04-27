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
import java.time.Duration;
import org.mindrot.jbcrypt.BCrypt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserServiceImpl implements UserService {

    private static final Logger log = LoggerFactory.getLogger(UserServiceImpl.class);
    private static final String JWT_BLACKLIST_PREFIX = "jwt:blacklist:";

    private final UserMapper userMapper;
    private final JwtUtil jwtUtil;
    private final RedisUtil redisUtil;

    /**
     * 初始化UserServiceImpl 实例。
     * @param userMapper u se rM ap pe r 参数
     * @param jwtUtil j wt Ut il 参数
     * @param redisUtil r ed is Ut il 参数
     */
    public UserServiceImpl(UserMapper userMapper, JwtUtil jwtUtil, RedisUtil redisUtil) {
        this.userMapper = userMapper;
        this.jwtUtil = jwtUtil;
        this.redisUtil = redisUtil;
    }

    /**
     * 处理register。
     * @param request 请求参数
     * @return 返回处理结果。
     */
    @Override
    @Transactional
    public User register(RegisterRequest request) {
        log.info("Register request received: username={}, email={}", request.getUsername(), request.getEmail());
        try {
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
            user.setUserLevel(1);
            user.setStatus(1);

            userMapper.insert(user);
            log.info("New user registered: id={}, email={}", user.getId(), user.getEmail());
            user.setPasswordHash(null);
            return user;
        } catch (BusinessException ex) {
            log.warn("Register request rejected: username={}, email={}, reason={}",
                request.getUsername(), request.getEmail(), ex.getMessage());
            throw ex;
        } catch (RuntimeException ex) {
            log.error("Register request failed unexpectedly: username={}, email={}",
                request.getUsername(), request.getEmail(), ex);
            throw ex;
        }
    }

    /**
     * 处理login。
     * @param request 请求参数
     * @return 返回处理结果。
     */
    @Override
    public LoginResponse login(LoginRequest request) {
        User user = userMapper.findByUsername(request.getUsername());
        if (user == null || !user.isActive()) {
            throw new BusinessException(401, "用户名或密码错误");
        }
        if (!BCrypt.checkpw(request.getPassword(), user.getPasswordHash())) {
            throw new BusinessException(401, "用户名或密码错误");
        }

        String token = jwtUtil.generateToken(user.getId(), user.getUserLevel());
        long expiresAt = jwtUtil.getExpirationFromToken(token).getTime();

        log.info("User logged in: id={}, username={}", user.getId(), user.getUsername());
        return new LoginResponse(token, user.getId(), user.getUsername(),
            user.getUserLevel(), expiresAt);
    }

    /**
     * 处理logout。
     * @param token t ok en 参数
     */
    @Override
    public void logout(String token) {
        if (token == null || token.isBlank()) {
            return;
        }
        long remainingMs = jwtUtil.getRemainingValidityMs(token);
        if (remainingMs > 0) {
            redisUtil.set(JWT_BLACKLIST_PREFIX + token, "1",
                Duration.ofMillis(remainingMs + 1000));
        }
        log.info("Token blacklisted (logout), remaining validity: {}ms", remainingMs);
    }

    /**
     * 取消account。
     * @param userId 用户ID
     */
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

    /**
     * 查找byid。
     * @param userId 用户ID
     * @return 返回处理结果。
     */
    @Override
    public User findById(Long userId) {
        User user = userMapper.findById(userId);
        if (user != null) {
            user.setPasswordHash(null);
        }
        return user;
    }

    /**
     * 更新userlevel。
     * @param userId 用户ID
     * @param newLevel n ew Le ve l 参数
     */
    @Override
    @Transactional
    public void updateUserLevel(Long userId, int newLevel) {
        User user = new User();
        user.setId(userId);
        user.setUserLevel(newLevel);
        userMapper.update(user);
        log.info("User level updated: userId={}, newLevel={}", userId, newLevel);
    }
}
