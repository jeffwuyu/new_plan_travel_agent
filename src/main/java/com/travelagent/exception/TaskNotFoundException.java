package com.travelagent.exception;

/**
 * 中文注释：异常类，用于表达 Task Not Found Exception 场景下的错误语义。
 */

public class TaskNotFoundException extends BusinessException {

    /**
     * 初始化TaskNotFoundException 实例。
     * @param taskUuid 任务唯一标识
     */
    public TaskNotFoundException(String taskUuid) {
        super(404, "任务不存在: " + taskUuid);
    }
}
