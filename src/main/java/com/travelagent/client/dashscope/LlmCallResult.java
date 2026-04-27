package com.travelagent.client.dashscope;

/**
 * 初始化LlmCallResult 实例。
 * @param content 内容
 * @param totalTokens t ot al To ke ns 参数
 */
public record LlmCallResult(String content, int totalTokens) {}
