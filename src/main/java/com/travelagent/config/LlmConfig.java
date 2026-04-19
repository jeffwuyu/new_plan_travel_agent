package com.travelagent.config;

import com.alibaba.cloud.ai.dashscope.api.DashScopeApi;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
import com.alibaba.dashscope.aigc.generation.Generation;
import com.alibaba.dashscope.embeddings.TextEmbedding;
import io.micrometer.observation.ObservationRegistry;
import okhttp3.OkHttpClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;

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

    @Value("${dashscope.llm.model:qwen-plus}")
    private String dashscopeModel;

    @Value("${dashscope.llm.temperature:0.7}")
    private double dashscopeTemperature;

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
     * Create the Spring AI chat model explicitly from dashscope.* properties.
     */
    @Bean
    @ConditionalOnMissingBean(ChatModel.class)
    public ChatModel dashscopeChatModel() {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout((int) timeoutMs);
        requestFactory.setReadTimeout((int) timeoutMs);

        RestClient.Builder restClientBuilder = RestClient.builder()
                .requestFactory(requestFactory);

        WebClient.Builder webClientBuilder = WebClient.builder();

        DashScopeApi dashScopeApi = DashScopeApi.builder()
                .apiKey(dashscopeApiKey)
                .restClientBuilder(restClientBuilder)
                .webClientBuilder(webClientBuilder)
                .build();

        DashScopeChatOptions options = new DashScopeChatOptions();
        options.setModel(dashscopeModel);
        options.setTemperature(dashscopeTemperature);

        return DashScopeChatModel.builder()
                .dashScopeApi(dashScopeApi)
                .defaultOptions(options)
                .observationRegistry(ObservationRegistry.NOOP)
                .build();
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
