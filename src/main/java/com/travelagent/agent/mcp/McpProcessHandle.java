package com.travelagent.agent.mcp;

import java.io.BufferedReader;
import java.io.BufferedWriter;

/**
 * 初始化McpProcessHandle 实例。
 * @param process p ro ce ss 参数
 * @param stdin s td in 参数
 * @param stdout s td ou t 参数
 * @param stderr s td er r 参数
 */
public record McpProcessHandle(
        Process process,
        BufferedWriter stdin,
        BufferedReader stdout,
        BufferedReader stderr
) {
}
