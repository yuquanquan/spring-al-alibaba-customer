package com.example.smartcs.listener;

import com.example.smartcs.event.MemoryPersistEvent;
import com.example.smartcs.service.ChatMemoryService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 记忆持久化监听器（异步处理）
 * <p>
 * ========================================================================
 * 【学习要点: 事件驱动 + 异步处理】
 * ========================================================================
 * 使用 @TransactionalEventListener + @Async 实现：
 * 1. 事务提交后才触发（保证数据一致性）
 * 2. 异步执行不阻塞主流程（性能优化）
 * 3. 故障隔离（记忆写入失败不影响对话）
 * <p>
 * 执行时机：
 * - TransactionPhase.AFTER_COMMIT: 事务成功提交后触发
 * - 如果事务回滚，事件不会发布（避免脏数据）
 */
@Slf4j
@Component
public class MemoryPersistListener {

    @Autowired
    private ChatMemoryService memoryService;

    /**
     * 异步处理记忆持久化
     * <p>
     * 使用专用线程池 "memoryExecutor" 执行，不占用主业务线程。
     *
     * @param event 记忆持久化事件
     */
    @Async("memoryExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onMemoryPersist(MemoryPersistEvent event) {
        long start = System.currentTimeMillis();
        
        try {
            log.debug("【异步记忆】开始处理: sessionId={}, role={}", 
                event.getSessionId(), event.getRole());

            // 步骤1: 保存到长期记忆（PostgreSQL）
            if ("USER".equals(event.getRole())) {
                memoryService.saveUserMessage(event.getSessionId(), event.getContent());
            } else if ("ASSISTANT".equals(event.getRole())) {
                memoryService.saveAssistantMessage(event.getSessionId(), event.getContent());
            }

            // 步骤2: 检查是否需要压缩记忆
            memoryService.compressIfNeeded(event.getSessionId());

            long duration = System.currentTimeMillis() - start;
            log.info("【异步记忆】完成: sessionId={}, duration={}ms", 
                event.getSessionId(), duration);

        } catch (Exception e) {
            // ⚠️ 关键：这里不能抛出异常，否则会影响主流程
            log.error("【异步记忆】失败: sessionId={}, role={}, error={}", 
                event.getSessionId(), event.getRole(), e.getMessage(), e);
            
            // TODO: 可以考虑发送到告警系统或重试队列
            // alertService.send("记忆持久化失败", event);
        }
    }
}
