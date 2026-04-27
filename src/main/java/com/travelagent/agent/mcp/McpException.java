package com.travelagent.agent.mcp;

public class McpException extends RuntimeException {

    /**
     * 初始化McpException 实例。
     * @param message 提示信息
     */
    public McpException(String message) {
        super(message);
    }

    /**
     * 初始化McpException 实例。
     * @param message 提示信息
     * @param cause c au se 参数
     */
    public McpException(String message, Throwable cause) {
        super(message, cause);
    }
}
