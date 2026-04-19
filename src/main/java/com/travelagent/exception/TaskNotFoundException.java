package com.travelagent.exception;

/**
 * 中文注释：异常类，用于表达 Task Not Found Exception 场景下的错误语义。
 */

public class TaskNotFoundException extends BusinessException {

    public TaskNotFoundException(String taskUuid) {
        super(404, "任务不存在: " + taskUuid);
    }
}
