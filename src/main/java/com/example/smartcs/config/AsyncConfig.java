package com.example.smartcs.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 异步任务配置
 * <p>
 * ========================================================================
 * 【学习要点: 异步线程池】
 * ========================================================================
 * 为记忆持久化提供专用线程池，避免影响主业务流程。
 * <p>
 * 线程池参数说明：
 * - corePoolSize: 核心线程数（常驻线程）
 * - maxPoolSize: 最大线程数（高峰期扩容）
 * - queueCapacity: 队列容量（缓冲待处理任务）
 * - rejectedExecutionHandler: 拒绝策略（队列满时的处理方式）
 */
@Configuration
@EnableAsync  // 启用异步支持
public class AsyncConfig {

    /**
     * 记忆持久化专用线程池
     * <p>
     * 独立于主业务线程池，避免记忆写入阻塞其他异步任务。
     *
     * @return 线程池实例
     */
    @Bean("memoryExecutor")
    public Executor memoryExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        
        // 核心线程数：2个常驻线程处理记忆任务
        executor.setCorePoolSize(2);
        
        // 最大线程数：高峰期最多5个线程
        executor.setMaxPoolSize(5);
        
        // 队列容量：最多缓存1000个待处理任务
        executor.setQueueCapacity(1000);
        
        // 线程名前缀：便于日志追踪
        executor.setThreadNamePrefix("memory-async-");
        
        // 拒绝策略：队列满时由调用线程执行（保证不丢失）
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        
        // 空闲线程存活时间（秒）
        executor.setKeepAliveSeconds(60);
        
        // 等待所有任务结束后再关闭线程池
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(60);
        
        executor.initialize();
        
        return executor;
    }
}
