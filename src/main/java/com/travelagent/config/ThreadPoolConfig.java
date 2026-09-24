package com.travelagent.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * Thread pool configuration for async agent task execution.
 *
 * agentTaskExecutor - Long-running agent task loops (plan, tool calls)
 * ragIngestionExecutor - RAG document ingestion (chunking + embedding)
 * routeMapExecutor - Route map rendering, OSS upload and image generation polling
 */

/**
 * 中文注释：配置类，用于集中声明 Thread Pool Config 相关的 Spring Bean 或运行参数。
 */

@Configuration
public class ThreadPoolConfig {

    @Value("${agent.task.thread-pool-size:4}")
    private int agentPoolSize;

    @Value("${agent.task.queue-size:100}")
    private int agentQueueSize;

    @Value("${rag.ingestion.thread-pool-size:2}")
    private int ragIngestionPoolSize;

    @Value("${rag.ingestion.queue-size:50}")
    private int ragIngestionQueueSize;

    @Value("${route-map.thread-pool-size:2}")
    private int routeMapPoolSize;

    @Value("${route-map.queue-size:50}")
    private int routeMapQueueSize;

    /**
     * 处理agentTaskExecutor。
     * @return 返回处理结果。
     */
    @Bean(name = "agentTaskExecutor")
    public ThreadPoolTaskExecutor agentTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(agentPoolSize);
        executor.setMaxPoolSize(agentPoolSize * 2);
        executor.setQueueCapacity(agentQueueSize);
        executor.setThreadNamePrefix("agent-task-");
        executor.setKeepAliveSeconds(60);
        // Caller runs when queue is full — prevents task loss
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }

    /**
     * 处理ragIngestionExecutor。
     * @return 返回处理结果。
     */
    @Bean(name = "ragIngestionExecutor")
    public ThreadPoolTaskExecutor ragIngestionExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(ragIngestionPoolSize);
        executor.setMaxPoolSize(Math.max(ragIngestionPoolSize, ragIngestionPoolSize * 2));
        executor.setQueueCapacity(ragIngestionQueueSize);
        executor.setThreadNamePrefix("rag-ingest-");
        executor.setKeepAliveSeconds(120);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }

    @Bean(name = "routeMapExecutor")
    public ThreadPoolTaskExecutor routeMapExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(routeMapPoolSize);
        executor.setMaxPoolSize(Math.max(routeMapPoolSize, routeMapPoolSize * 2));
        executor.setQueueCapacity(routeMapQueueSize);
        executor.setThreadNamePrefix("route-map-");
        executor.setKeepAliveSeconds(120);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }
}
