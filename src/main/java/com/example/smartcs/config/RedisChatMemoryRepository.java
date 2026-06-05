package com.example.smartcs.config;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Redis 实现的 ChatMemoryRepository（Layer 1: 工作记忆）
 * <p>
 * ========================================================================
 * 【企业级设计: Redis 分布式滑动窗口】
 * ========================================================================
 * <p>
 * 替代本地内存 MessageWindowChatMemory 的原因：
 * <pre>
 *   本地内存方案:
 *     - 应用重启 → 对话丢失 ❌
 *     - 多实例部署 → 会话不共享 ❌
 *     - JVM 堆内存 → 随会话量线性增长 ❌
 *
 *   Redis 方案:
 *     - 持久化存储 → 重启不丢失 ✅
 *     - 分布式共享 → 多实例可用 ✅
 *     - 自动过期 → TTL 自动清理 ✅
 *     - 读取延迟 → < 1ms（Redis 内存级） ✅
 * </pre>
 * <p>
 * 存储结构:
 * <pre>
 *   Redis Key: chat:memory:{sessionId}
 *   Redis Type: List
 *   Redis Value: JSON 序列化的消息 [{"role":"USER","content":"..."}, ...]
 *   TTL: 可配置（默认24小时）
 * </pre>
 * <p>
 * 与 MessageWindowChatMemory 配合使用：
 * MessageWindowChatMemory 负责滑动窗口逻辑（保留最近N条），
 * 本类负责底层存储（Redis List 读写 + TTL管理）。
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
@Slf4j
public class RedisChatMemoryRepository implements ChatMemoryRepository {

    private static final String KEY_PREFIX = "chat:memory:";

    private static final ObjectMapper MAPPER = new ObjectMapper()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private final StringRedisTemplate redisTemplate;
    private final long ttlSeconds;

    public RedisChatMemoryRepository(StringRedisTemplate redisTemplate, long ttlSeconds) {
        this.redisTemplate = redisTemplate;
        this.ttlSeconds = ttlSeconds;
    }

    /**
     * 查询所有会话ID（管理接口，生产环境慎用 KEYS 命令）
     */
    @Override
    public List<String> findConversationIds() {
        Set<String> keys = redisTemplate.keys(KEY_PREFIX + "*");
        if (keys == null || keys.isEmpty()) {
            return List.of();
        }
        return keys.stream()
            .map(key -> key.substring(KEY_PREFIX.length()))
            .collect(Collectors.toList());
    }

    /**
     * 读取会话的全部消息（Redis List LRANGE 0 -1）
     * <p>
     * MessageWindowChatMemory 会在此基础上做滑动窗口裁剪。
     */
    @Override
    public List<Message> findByConversationId(String conversationId) {
        String key = KEY_PREFIX + conversationId;
        List<String> rawMessages = redisTemplate.opsForList().range(key, 0, -1);

        if (rawMessages == null || rawMessages.isEmpty()) {
            return new ArrayList<>();
        }

        List<Message> messages = new ArrayList<>();
        for (String raw : rawMessages) {
            try {
                MessageRecord record = MAPPER.readValue(raw, MessageRecord.class);
                if (MessageType.USER.getValue().equals(record.role())) {
                    messages.add(new UserMessage(record.content()));
                } else if (MessageType.ASSISTANT.getValue().equals(record.role())) {
                    messages.add(new AssistantMessage(record.content()));
                }
            } catch (JsonProcessingException e) {
                log.warn("【Redis记忆】消息反序列化失败，跳过: {}", e.getMessage());
            }
        }
        return messages;
    }

    /**
     * 全量写入会话消息（先删后写，保证一致性）
     * <p>
     * MessageWindowChatMemory 每次调用时传入裁剪后的完整列表，
     * 这里直接覆盖 Redis 中的旧数据。
     */
    @Override
    public void saveAll(String conversationId, List<Message> messages) {
        String key = KEY_PREFIX + conversationId;

        // 先清除旧数据
        redisTemplate.delete(key);

        if (messages == null || messages.isEmpty()) {
            return;
        }

        // 序列化为 JSON 并写入 Redis List
        List<String> serialized = messages.stream()
            .map(msg -> {
                try {
                    return MAPPER.writeValueAsString(
                        new MessageRecord(msg.getMessageType().getValue(), msg.getText())
                    );
                } catch (JsonProcessingException e) {
                    log.warn("【Redis记忆】消息序列化失败: {}", e.getMessage());
                    return null;
                }
            })
            .filter(Objects::nonNull)
            .collect(Collectors.toList());

        if (!serialized.isEmpty()) {
            redisTemplate.opsForList().rightPushAll(key, serialized);
            redisTemplate.expire(key, ttlSeconds, TimeUnit.SECONDS);
        }
    }

    /**
     * 删除会话记忆
     */
    @Override
    public void deleteByConversationId(String conversationId) {
        redisTemplate.delete(KEY_PREFIX + conversationId);
    }

    /**
     * 消息序列化记录（JSON 友好）
     */
    record MessageRecord(String role, String content) {}
}
