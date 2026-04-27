package com.travelagent.util;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * JSON serialization utility wrapping the shared ObjectMapper.
 * Used primarily for TaskCheckpoint serialization/deserialization.
 */

/**
 * 中文注释：工具类，封装 Json Util 相关的通用辅助能力。
 */

@Component
public class JsonUtil {

    @Autowired
    private ObjectMapper objectMapper;

    /**
     * 将数据转换为json。
     * @param obj o bj 参数
     * @return 返回处理结果。
     */
    public String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            throw new IllegalStateException("JSON serialization failed", e);
        }
    }

    /**
     * 将。
     * @param json JSON字符串
     * @param clazz 目标类型
     * @return 返回处理结果。
     */
    public <T> T fromJson(String json, Class<T> clazz) {
        try {
            return objectMapper.readValue(json, clazz);
        } catch (Exception e) {
            throw new IllegalStateException("JSON deserialization failed for class: " + clazz.getName(), e);
        }
    }

    /**
     * 将。
     * @param json JSON字符串
     * @param typeRef t yp eR ef 参数
     * @return 返回处理结果。
     */
    public <T> T fromJson(String json, TypeReference<T> typeRef) {
        try {
            return objectMapper.readValue(json, typeRef);
        } catch (Exception e) {
            throw new IllegalStateException("JSON deserialization failed", e);
        }
    }
}
