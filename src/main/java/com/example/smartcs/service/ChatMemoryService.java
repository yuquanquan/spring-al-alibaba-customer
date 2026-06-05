package com.example.smartcs.service;

import com.example.smartcs.entity.ChatHistory;
import com.example.smartcs.repository.ChatHistoryRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 对话记忆服务（长期记忆 + 记忆压缩）
 * <p>
 * ========================================================================
 * 【学习要点: 对话记忆】
 * ========================================================================
 * 多轮对话需要记忆历史上下文，否则 AI 会"失忆"。
 * <p>
 * 两级记忆架构：
 * 1. 短期记忆（Short-term Memory）:
 *    - 存储在内存中（MessageWindowChatMemory）
 *    - 保留最近 N 条消息（滑动窗口，自动淘汰旧消息）
 *    - 会话结束后自动释放
 *    - 由 ChatService 直接调用 shortTermMemory.add()/get()
 * <p>
 * 2. 长期记忆（Long-term Memory）:
 *    - 持久化到 PostgreSQL（本类负责）
 *    - 跨会话保留（用户下次登录仍可恢复上下文）
 *    - 支持记忆压缩（减少 Token 消耗）
 * <p>
 * 3. 记忆压缩（Memory Compression）:
 *    - 当历史消息超过阈值时，调用 LLM 总结对话
 *    - 用"摘要"替代原始对话，节省 Token
 *    - 例如：100条消息 → 1段总结（Token 从 5000 → 200）
 */
@Slf4j
@Service
public class ChatMemoryService {

    @Autowired
    private ChatHistoryRepository historyRepo;

    @Autowired
    private ChatClient chatClient;

    /** 短期记忆窗口大小（消息数） */
    private static final int SHORT_TERM_WINDOW = 10;

    /** 触发压缩的消息数阈值 */
    private static final int COMPRESSION_THRESHOLD = 20;

    /** 压缩后保留的总结消息角色 */
    private static final String SUMMARY_ROLE = "SYSTEM";

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
     * 获取完整历史记忆（包括已压缩的总结）
     * <p>
     * 用于长期对话恢复或深度分析
     */
    public List<ChatHistory> getFullHistory(String sessionId) {
        return historyRepo.findBySessionIdOrderByMessageIndexAsc(sessionId);
    }

    /**
     * 检查并执行记忆压缩
     * <p>
     * 当未压缩消息数超过阈值时，触发压缩流程：
     * 1. 提取所有未压缩的历史消息
     * 2. 调用 LLM 生成总结
     * 3. 保存总结为 SYSTEM 消息
     * 4. 标记旧消息为已压缩
     * 5. 删除已压缩的旧消息（可选，节省空间）
     */
    @Transactional
    public void compressIfNeeded(String sessionId) {
        long uncompressedCount = historyRepo.countBySessionId(sessionId);

        if (uncompressedCount >= COMPRESSION_THRESHOLD) {
            log.info("【记忆压缩】触发压缩: sessionId={}, 消息数={}", sessionId, uncompressedCount);

            // 步骤1: 获取未压缩的历史
            List<ChatHistory> uncompressedHistory = historyRepo.findUncompressedHistory(sessionId);

            // 步骤2: 构建压缩 Prompt
            String conversationText = uncompressedHistory.stream()
                .map(h -> h.getRole() + ": " + h.getContent())
                .collect(Collectors.joining("\n"));

            String summary = summarizeConversation(conversationText);

            // 步骤3: 保存总结
            ChatHistory summaryMsg = new ChatHistory();
            summaryMsg.setSessionId(sessionId);
            summaryMsg.setRole(SUMMARY_ROLE);
            summaryMsg.setContent("【对话总结】\n" + summary);
            summaryMsg.setMessageIndex(getNextMessageIndex(sessionId));
            summaryMsg.setTokenCount(estimateTokens(summary));
            summaryMsg.setCreateTime(LocalDateTime.now());
            summaryMsg.setCompressed(true);  // 总结本身不压缩

            historyRepo.save(summaryMsg);

            // 步骤4: 标记旧消息为已压缩
            for (ChatHistory h : uncompressedHistory) {
                h.setCompressed(true);
            }
            historyRepo.saveAll(uncompressedHistory);

            // 步骤5: 删除已压缩的旧消息（可选，根据存储策略决定）
            // historyRepo.deleteBySessionIdAndCompressedTrue(sessionId);

            log.info("【记忆压缩】完成: 原{}条消息 → 1条总结", uncompressedCount);
        }
    }

    /**
     * 使用 LLM 总结对话历史
     */
    private String summarizeConversation(String conversation) {
        String prompt = String.format("""
            请对以下对话历史进行简洁总结，提取关键信息：
            
            %s
            
            要求：
            1. 用一段话概括对话主题和结论
            2. 保留重要的事实信息（如订单号、用户名等）
            3. 控制在 200 字以内
            4. 不要包含无关的寒暄内容
            """, conversation);

        return chatClient.prompt()
            .user(prompt)
            .call()
            .content();
    }

    /**
     * 获取下一条消息的序号
     */
    private int getNextMessageIndex(String sessionId) {
        return (int) historyRepo.countBySessionId(sessionId) + 1;
    }

    /**
     * 估算 Token 数量（简化版）
     * <p>
     * 生产环境建议使用 Tiktoken 库进行精确计算
     */
    private int estimateTokens(String text) {
        // 粗略估算：中文约 1 字符 = 1.5 token，英文约 4 字符 = 1 token
        int chineseChars = (int) text.chars().filter(c -> c >= 0x4E00 && c <= 0x9FFF).count();
        int otherChars = text.length() - chineseChars;
        return (int) (chineseChars * 1.5 + otherChars / 4);
    }

    /**
     * 清空会话记忆
     */
    @Transactional
    public void clearMemory(String sessionId) {
        historyRepo.deleteBySessionIdAndCompressedTrue(sessionId);
        log.info("【记忆清理】清空会话: {}", sessionId);
    }
}
