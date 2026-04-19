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

    private Result() {}

    public static <T> Result<T> success(T data) {
        Result<T> r = new Result<>();
        r.code = 200;
        r.message = "success";
        r.data = data;
        return r;
    }

    public static <T> Result<T> success() {
        return success(null);
    }

    public static <T> Result<T> error(int code, String message) {
        Result<T> r = new Result<>();
        r.code = code;
        r.message = message;
        return r;
    }

    public static <T> Result<T> badRequest(String message) {
        return error(400, message);
    }

    public static <T> Result<T> unauthorized(String message) {
        return error(401, message);
    }

    public static <T> Result<T> forbidden(String message) {
        return error(403, message);
    }

    public static <T> Result<T> notFound(String message) {
        return error(404, message);
    }

    public static <T> Result<T> tooManyRequests(String message) {
        return error(429, message);
    }

    public static <T> Result<T> serverError(String message) {
        return error(500, message);
    }

    public boolean isSuccess() {
        return code == 200;
    }
}
