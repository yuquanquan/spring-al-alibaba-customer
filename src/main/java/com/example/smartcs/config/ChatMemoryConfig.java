package com.example.smartcs.config;

import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 对话记忆配置（Redis 两层架构）
 * <p>
 * 两层记忆架构：
 * <pre>
 *   Layer 1: 工作记忆（Redis 滑动窗口）
 *            RedisChatMemoryRepository 负责存储
 *            MessageWindowChatMemory 负责滑动窗口裁剪（保留最近N条）
 *
 *   Layer 2: 画像记忆（Redis Hash + PostgreSQL）
 *            FactExtractor 负责事实提取
 *            Redis Hash 缓存加速读取（chat:profile:{sessionId}）
 * </pre>
 * <p>
 * ========================================================================
 * 【本地滑动窗口方案备忘】（开发/测试环境可用，无需 Redis）
 * ========================================================================
 * <pre>
 * 如果不使用 Redis，可以用本地内存方案：
 *
 *   &#64;Bean
 *   public ChatMemory shortTermMemory() {
 *       return MessageWindowChatMemory.builder()
 *           .maxMessages(10)
 *           .build();
 *   }
 *
 * 优点: 零依赖，速度最快（< 0.1ms）
 * 缺点: 重启丢失，不支持分布式
 * </pre>
 */
@Configuration
public class ChatMemoryConfig {

    @Value("${app.chat-memory.window-size:10}")
    private int windowSize;

    @Value("${app.chat-memory.ttl-seconds:86400}")
    private long ttlSeconds;

    /**
     * Redis 记忆存储器（Layer 1 底层存储）
     * <p>
     * 负责 Redis List 的读写 + TTL 管理。
     * MessageWindowChatMemory 调用它的 saveAll/findByConversationId 方法。
     */
    @Bean
    public RedisChatMemoryRepository redisChatMemoryRepository(StringRedisTemplate redisTemplate) {
        return new RedisChatMemoryRepository(redisTemplate, ttlSeconds);
    }

    /**
     * 短期记忆：Redis 滑动窗口
     * <p>
     * MessageWindowChatMemory 负责滑动窗口逻辑（保留最近 N 条），
     * RedisChatMemoryRepository 负责底层存储（Redis List + TTL）。
     * <p>
     * 速度: < 1ms（Redis 内存级）
     * 会话隔离: 每个 sessionId 独立 Redis Key
     * 持久化: 应用重启不丢失
     */
    @Bean
    public ChatMemory shortTermMemory(RedisChatMemoryRepository repository) {
        return MessageWindowChatMemory.builder()
            .chatMemoryRepository(repository)
            .maxMessages(windowSize)
            .build();
    }
}
