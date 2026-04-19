package com.travelagent.config;

import com.alibaba.dashscope.aigc.generation.Generation;
import com.alibaba.dashscope.embeddings.TextEmbedding;
import okhttp3.OkHttpClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

/**
 * Dashscope (Tongyi Qianwen) LLM and Embedding client configuration.
 * API key is injected from application.properties.
 */

/**
 * 中文注释：配置类，用于集中声明 Llm Config 相关的 Spring Bean 或运行参数。
 */

@Configuration
public class LlmConfig {

    @Value("${dashscope.api-key}")
    private String dashscopeApiKey;

    @Value("${dashscope.timeout:60000}")
    private long timeoutMs;

    /**
     * Dashscope Generation client for Tongyi Qianwen LLM calls.
     * The API key is set globally via system property before use,
     * or passed per-call in DashscopeLlmClient.
     */
    @Bean
    public Generation dashscopeGeneration() {
        return new Generation();
    }

    /**
     * Dashscope TextEmbedding client for text-embedding-v3.
     */
    @Bean
    public TextEmbedding dashscopeTextEmbedding() {
        return new TextEmbedding();
    }

    /**
     * Shared OkHttpClient for Amap and DashVector REST calls.
     * Configured with appropriate timeouts.
     */
    @Bean
    public OkHttpClient okHttpClient() {
        return new OkHttpClient.Builder()
            .connectTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .writeTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(true)
            .build();
    }

    public String getDashscopeApiKey() {
        return dashscopeApiKey;
    }
}
