package com.travelagent.client.dashscope;

/**
 * Wraps the output of a single LLM call: the generated text and the total tokens consumed.
 * totalTokens is 0 when the underlying call used streaming mode and the provider did not
 * return usage metadata in the stream (known Dashscope limitation).
 */
public record LlmCallResult(String content, int totalTokens) {}
