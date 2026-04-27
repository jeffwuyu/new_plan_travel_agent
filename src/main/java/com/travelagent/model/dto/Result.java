package com.travelagent.model.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

/**
 * Unified API response wrapper.
 * All REST endpoints return this structure: {code, message, data}
 *
 * Success: code=200
 * Business error: code=4xx (e.g. 400, 401, 403, 404, 429)
 * System error: code=500
 */

/**
 * 中文注释：DTO 类，用于在接口或服务之间传递 Result 数据。
 */

@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Result<T> {

    private int code;
    private String message;
    private T data;

    /**
     * 初始化Result 实例。
     */
    private Result() {}

    /**
     * 处理success。
     * @param data 响应数据
     * @return 返回统一封装后的响应结果。
     */
    public static <T> Result<T> success(T data) {
        Result<T> r = new Result<>();
        r.code = 200;
        r.message = "success";
        r.data = data;
        return r;
    }

    /**
     * 处理success。
     * @return 返回统一封装后的响应结果。
     */
    public static <T> Result<T> success() {
        return success(null);
    }

    /**
     * 处理error。
     * @param code 状态码
     * @param message 提示信息
     * @return 返回统一封装后的响应结果。
     */
    public static <T> Result<T> error(int code, String message) {
        Result<T> r = new Result<>();
        r.code = code;
        r.message = message;
        return r;
    }

    /**
     * 处理badRequest。
     * @param message 提示信息
     * @return 返回统一封装后的响应结果。
     */
    public static <T> Result<T> badRequest(String message) {
        return error(400, message);
    }

    /**
     * 处理unauthorized。
     * @param message 提示信息
     * @return 返回统一封装后的响应结果。
     */
    public static <T> Result<T> unauthorized(String message) {
        return error(401, message);
    }

    /**
     * 处理forbidden。
     * @param message 提示信息
     * @return 返回统一封装后的响应结果。
     */
    public static <T> Result<T> forbidden(String message) {
        return error(403, message);
    }

    /**
     * 处理notFound。
     * @param message 提示信息
     * @return 返回统一封装后的响应结果。
     */
    public static <T> Result<T> notFound(String message) {
        return error(404, message);
    }

    /**
     * 处理tooManyRequests。
     * @param message 提示信息
     * @return 返回统一封装后的响应结果。
     */
    public static <T> Result<T> tooManyRequests(String message) {
        return error(429, message);
    }

    /**
     * 处理serverError。
     * @param message 提示信息
     * @return 返回统一封装后的响应结果。
     */
    public static <T> Result<T> serverError(String message) {
        return error(500, message);
    }

    /**
     * 判断success。
     * @return 是否满足当前条件。
     */
    public boolean isSuccess() {
        return code == 200;
    }
}
