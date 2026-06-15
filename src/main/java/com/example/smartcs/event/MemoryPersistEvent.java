package com.example.smartcs.event;

import lombok.Getter;
import org.springframework.context.ApplicationEvent;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * 记忆持久化事件
 * <p>
 * ========================================================================
 * 【学习要点: 事件驱动架构】
 * ========================================================================
 * 使用 Spring Event 实现异步解耦：
 * 1. 主流程发布事件（非阻塞，< 1ms）
 * 2. 异步监听器处理持久化（后台线程池）
 * 3. 故障隔离：持久化失败不影响对话
 * <p>
 * 优势：
 * - 性能提升：主流程减少 ~20ms（数据库写入）+ ~3000ms（压缩）
 * - 可扩展：可以轻松添加更多监听器（如用户画像、数据分析）
 * - 可靠性：事务提交后才触发，保证数据一致性
 */
@Getter
public class MemoryPersistEvent extends ApplicationEvent {

    private final String sessionId;
    private final String role;        // USER 用户会话/ ASSISTANT 回复内容
    private final String content;

    public MemoryPersistEvent(Object source, String sessionId, String role, String content) {
        super(source);
        this.sessionId = sessionId;
        this.role = role;
        this.content = content;
    }
}
