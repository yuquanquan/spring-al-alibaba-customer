package com.example.smartcs.service;

import com.example.smartcs.entity.ChatHistory;
import com.example.smartcs.repository.ChatHistoryRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 对话记忆服务（长期记忆持久化 + 事实提取触发）
 * <p>
 * ========================================================================
 * 【企业级设计: 两层记忆架构】
 * ========================================================================
 * <pre>
 * Layer 1: 工作记忆（Redis 滑动窗口）
 *   - 由 RedisChatMemoryRepository + MessageWindowChatMemory 管理
 *   - 最近10条消息，Redis List 存储
 *   - 由 ChatService 直接调用 shortTermMemory.add()/get()
 *
 * Layer 2: 画像记忆（结构化事实，永不压缩）
 *   - 由 FactExtractor 管理
 *   - 本类负责触发: 当未压缩消息达到阈值时，触发事实提取
 *   - 事实 UPSERT 到 Redis Hash + PostgreSQL
 *
 * 为什么删除了滚动摘要（情景层）:
 *   - 画像层已覆盖摘要的全部价值（结构化事实 > 叙述性摘要）
 *   - 删除后: 省一半 LLM 调用开销，提示词更精简
 * </pre>
 */
@Slf4j
@Service
public class ChatMemoryService {

    @Autowired
    private ChatHistoryRepository historyRepo;

    @Autowired
    private FactExtractor factExtractor;

    /** 触发事实提取的消息数阈值 */
    private static final int COMPRESSION_THRESHOLD = 20;

    /**
     * 保存用户消息到长期记忆
     */
    @Transactional
    public void saveUserMessage(String sessionId, String content) {
        ChatHistory history = new ChatHistory();
        history.setSessionId(sessionId);
        history.setRole("USER");
        history.setContent(content);
        history.setMessageIndex(getNextMessageIndex(sessionId));
        history.setTokenCount(estimateTokens(content));
        history.setCreateTime(LocalDateTime.now());
        history.setCompressed(false);

        historyRepo.save(history);
        log.debug("【长期记忆】保存用户消息: sessionId={}, index={}", sessionId, history.getMessageIndex());
    }

    /**
     * 保存 AI 回复到长期记忆
     */
    @Transactional
    public void saveAssistantMessage(String sessionId, String content) {
        ChatHistory history = new ChatHistory();
        history.setSessionId(sessionId);
        history.setRole("ASSISTANT");
        history.setContent(content);
        history.setMessageIndex(getNextMessageIndex(sessionId));
        history.setTokenCount(estimateTokens(content));
        history.setCreateTime(LocalDateTime.now());
        history.setCompressed(false);

        historyRepo.save(history);
        log.debug("【长期记忆】保存AI回复: sessionId={}, index={}", sessionId, history.getMessageIndex());
    }

    /**
     * 获取完整历史记忆
     */
    public List<ChatHistory> getFullHistory(String sessionId) {
        return historyRepo.findBySessionIdOrderByMessageIndexAsc(sessionId);
    }

    /**
     * 检查并触发事实提取（画像记忆 Layer 2）
     * <p>
     * ====================================================================
     * 【企业级优化: 只做事实提取，删除滚动摘要】
     * ====================================================================
     * <pre>
     * 之前: 压缩摘要(1次LLM) + 事实提取(1次LLM) = 2次调用/20条消息
     * 现在: 只做事实提取(1次LLM) = 1次调用/20条消息
     *
     * 画像记忆（Layer 2）是核心:
     *   - 结构化事实永不压缩，只 UPSERT
     *   - "user_name=张三" 在第1轮和第2000轮都一样精确
     *   - 滚动摘要已删除 — 画像层覆盖了摘要的全部价值
     * </pre>
     */
    @Transactional
    public void compressIfNeeded(String sessionId) {
        long uncompressedCount = historyRepo.countBySessionId(sessionId);

        if (uncompressedCount >= COMPRESSION_THRESHOLD) {
            log.info("【事实提取】触发: sessionId={}, 消息数={}", sessionId, uncompressedCount);

            // 获取未压缩的历史
            List<ChatHistory> uncompressedHistory = historyRepo.findUncompressedHistory(sessionId);

            String conversationText = uncompressedHistory.stream()
                .map(h -> h.getRole() + ": " + h.getContent())
                .collect(Collectors.joining("\n"));

            // ===== 核心: 只做事实提取（画像记忆 Layer 2）=====
            factExtractor.extractAndSaveFacts(sessionId, conversationText);

            // 标记旧消息为已压缩
            for (ChatHistory h : uncompressedHistory) {
                h.setCompressed(true);
            }
            historyRepo.saveAll(uncompressedHistory);

            log.info("【事实提取】完成: {}条消息已压缩", uncompressedCount);
        }
    }

    /**
     * 获取下一条消息的序号
     */
    private int getNextMessageIndex(String sessionId) {
        return (int) historyRepo.countBySessionId(sessionId) + 1;
    }

    /**
     * 估算 Token 数量（简化版）
     */
    private int estimateTokens(String text) {
        int chineseChars = (int) text.chars().filter(c -> c >= 0x4E00 && c <= 0x9FFF).count();
        int otherChars = text.length() - chineseChars;
        return (int) (chineseChars * 1.5 + otherChars / 4);
    }

    /** 清空会话记忆（含画像事实） */
    @Transactional
    public void clearMemory(String sessionId) {
        historyRepo.deleteBySessionIdAndCompressedTrue(sessionId);
        factExtractor.clearFacts(sessionId);
        log.info("【记忆清理】清空会话（含画像事实）: {}", sessionId);
    }
}
