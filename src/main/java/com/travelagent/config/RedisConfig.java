package com.travelagent.config;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.jsontype.impl.LaissezFaireSubTypeValidator;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.lettuce.core.ClientOptions;
import io.lettuce.core.SocketOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import org.apache.commons.pool2.impl.GenericObjectPoolConfig;
import org.springframework.data.redis.connection.lettuce.LettucePoolingClientConfiguration;

import java.time.Duration;

/**
 * Redis configuration using Lettuce client with connection pooling.
 * RedisTemplate<String, Object> uses Jackson serialization for values.
 * StringRedisTemplate is available for simple string operations (quota counters).
 */

/**
 * 中文注释：配置类，用于集中声明 Redis Config 相关的 Spring Bean 或运行参数。
 */

@Configuration
public class RedisConfig {

    @Value("${redis.host:127.0.0.1}")
    private String host;

    @Value("${redis.port:6379}")
    private int port;

    @Value("${redis.password:}")
    private String password;

    @Value("${redis.database:0}")
    private int database;

    @Value("${redis.timeout:3000}")
    private long timeout;

    @Value("${redis.pool.maxActive:8}")
    private int maxActive;

    @Value("${redis.pool.maxIdle:8}")
    private int maxIdle;

    @Value("${redis.pool.minIdle:0}")
    private int minIdle;

    @Value("${redis.pool.maxWait:3000}")
    private long maxWait;

    /**
     * 处理redisConnectionFactory。
     * @return 返回处理结果。
     */
    @Bean
    public RedisConnectionFactory redisConnectionFactory() {
        RedisStandaloneConfiguration standaloneConfig = new RedisStandaloneConfiguration(host, port);
        standaloneConfig.setDatabase(database);
        if (password != null && !password.isEmpty()) {
            standaloneConfig.setPassword(password);
        }

        GenericObjectPoolConfig<?> poolConfig = new GenericObjectPoolConfig<>();
        poolConfig.setMaxTotal(maxActive);
        poolConfig.setMaxIdle(maxIdle);
        poolConfig.setMinIdle(minIdle);
        poolConfig.setMaxWait(Duration.ofMillis(maxWait));
        poolConfig.setTestOnBorrow(true);
        poolConfig.setTestWhileIdle(true);

        LettucePoolingClientConfiguration clientConfig = LettucePoolingClientConfiguration.builder()
            .commandTimeout(Duration.ofMillis(timeout))
            .poolConfig(poolConfig)
            .clientOptions(ClientOptions.builder()
                .socketOptions(SocketOptions.builder()
                    .connectTimeout(Duration.ofMillis(timeout))
                    .build())
                .build())
            .build();

        return new LettuceConnectionFactory(standaloneConfig, clientConfig);
    }

    /**
     * 处理redisTemplate。
     * @param connectionFactory c on ne ct io nF ac to ry 参数
     * @return 返回处理结果。
     */
    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory connectionFactory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);

        StringRedisSerializer stringSerializer = new StringRedisSerializer();

        // Jackson serializer for values (supports complex objects)
        ObjectMapper om = new ObjectMapper();
        om.setVisibility(PropertyAccessor.ALL, JsonAutoDetect.Visibility.ANY);
        om.registerModule(new JavaTimeModule());
        om.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        om.activateDefaultTyping(LaissezFaireSubTypeValidator.instance,
            ObjectMapper.DefaultTyping.NON_FINAL);
        Jackson2JsonRedisSerializer<Object> jacksonSerializer =
                new Jackson2JsonRedisSerializer<>(om, Object.class);

        template.setKeySerializer(stringSerializer);
        template.setHashKeySerializer(stringSerializer);
        template.setValueSerializer(jacksonSerializer);
        template.setHashValueSerializer(jacksonSerializer);
        template.afterPropertiesSet();

        return template;
    }

    /**
     * 处理stringRedisTemplate。
     * @param connectionFactory c on ne ct io nF ac to ry 参数
     * @return 返回处理结果。
     */
    @Bean
    public StringRedisTemplate stringRedisTemplate(RedisConnectionFactory connectionFactory) {
        return new StringRedisTemplate(connectionFactory);
    }
}
