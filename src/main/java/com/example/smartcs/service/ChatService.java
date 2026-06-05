package com.example.smartcs.service;

import com.example.smartcs.config.PromptTemplates;
import com.example.smartcs.entity.ChatHistory;
import com.example.smartcs.event.MemoryPersistEvent;
import com.example.smartcs.model.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 智能客服核心编排服务
 * <p>
 * ========================================================================
 * 【学习要点: RAG 全链路编排】
 * ========================================================================
 * <p>
 * 这是整个系统的"大脑"，负责串联所有 AI 能力模块：
 * <p>
 * 完整处理流程:
 * <pre>
 *   用户输入
 *      │
 *      ▼
 *   ┌─────────────┐
 *   │  意图识别     │ ← IntentRecognizer (LLM分类)
 *   └─────┬───────┘
 *         │
 *    ┌────┴─────────────────────┐
 *    │                          │
 *    ▼ CHAT                     ▼ RAG / DB_QUERY
 * ┌──────────┐         ┌────────────────────┐
 * │ 直接回复   │         │  Query改写           │ ← QueryRewriter
 * └──────────┘         └────────┬───────────┘
 *                              │
 *                 ┌─────────────┴──────────┐
 *                 │                        │
 *                 ▼ RAG                    ▼ DB_QUERY
 *      ┌──────────────────┐     ┌──────────────────┐
 *      │ 多路召回           │     │ NL2SQL            │ ← DatabaseQueryService
 *      │ (向量+改写+子查询) │     │ (自然语言→SQL)     │
 *      └────────┬─────────┘     └────────┬─────────┘
 *               │                        │
 *               ▼                        ▼
 *      ┌──────────────────┐     ┌──────────────────┐
 *      │ LLM生成回答       │     │ LLM解读结果       │
 *      │ (注入检索上下文)   │     │ (SQL结果→自然语言) │
 *      └──────────────────┘     └──────────────────┘
 * </pre>
 * <p>
 * 关键设计：
 * 1. 意图识别决定路由方向
 * 2. RAG 链路: Query改写 → 多路召回 → 上下文注入 → LLM生成
 * 3. DB 链路: NL2SQL → 执行查询 → 结果解读 → LLM生成
 * 4. 每条链路独立降级，保证系统健壮性
 */
@Slf4j
@Service
public class ChatService {

    private final ChatClient chatClient;
    private final IntentRecognizer intentRecognizer;
    private final QueryRewriter queryRewriter;
    private final DocumentRetriever documentRetriever;
    private final DatabaseQueryService databaseQueryService;
    private final ChatMemory shortTermMemory;                     // 工作记忆（Redis 滑动窗口）
    private final ApplicationEventPublisher eventPublisher;       // 事件发布器
    private final FactExtractor factExtractor;                    // 画像记忆（Layer 2）

    public ChatService(ChatClient chatClient,
                       IntentRecognizer intentRecognizer,
                       QueryRewriter queryRewriter,
                       DocumentRetriever documentRetriever,
                       DatabaseQueryService databaseQueryService,
                       ChatMemory shortTermMemory,
                       ApplicationEventPublisher eventPublisher,
                       FactExtractor factExtractor) {
        this.chatClient = chatClient;
        this.intentRecognizer = intentRecognizer;
        this.queryRewriter = queryRewriter;
        this.documentRetriever = documentRetriever;
        this.databaseQueryService = databaseQueryService;
        this.shortTermMemory = shortTermMemory;
        this.eventPublisher = eventPublisher;
        this.factExtractor = factExtractor;
    }

    /**
     * 核心对话入口（事件驱动异步记忆）
     * <p>
     * 串联意图识别 → Query改写 → 多路召回/NL2SQL → 生成回答 的完整链路
     * <p>
     * 【优化点】长期记忆改为异步持久化：
     * - 短期记忆：同步写入 Redis（< 1ms，立即生效）
     * - 长期记忆：发布事件，异步写入数据库 + 事实提取（不阻塞主流程）
     *
     * @param sessionId 会话ID（用于记忆管理）
     * @param question 用户输入的问题
     * @return AI 生成的回答
     */
    public String chat(String sessionId, String question) {
        log.info("══════════════════════════════════════");
        log.info("【智能客服】会话: {}, 用户问题: {}", sessionId, question);

        // ===== 步骤0: 保存用户消息到短期记忆（同步） =====
        shortTermMemory.add(sessionId, new org.springframework.ai.chat.messages.UserMessage(question));
        
        // ===== 步骤0.5: 发布长期记忆事件（异步，非阻塞） =====
        eventPublisher.publishEvent(new MemoryPersistEvent(this, sessionId, "USER", question));

        // ===== 步骤1: 意图识别 =====
        // 快速路径: 简单问候直接走闲聊，避免 LLM 调用延迟
        ChatIntent intent;
        if (intentRecognizer.isLikelyChat(question)) {
            intent = new ChatIntent(IntentType.CHAT, 1.0, "关键词快速匹配");
        } else {
            intent = intentRecognizer.recognize(question);
        }
        log.info("【意图识别】→ {}", intent.intentType().getDescription());

        // ===== 步骤2: 根据意图路由到不同处理链路 =====
        String answer = switch (intent.intentType()) {
            case CHAT -> handleChat(sessionId, question);
            case RAG -> handleRagQuery(sessionId, question);
            case DB_QUERY -> handleDbQuery(sessionId, question);
        };

        // ===== 步骤3: 保存AI回复到短期记忆（同步） =====
        shortTermMemory.add(sessionId, new org.springframework.ai.chat.messages.AssistantMessage(answer));
        
        // ===== 步骤3.5: 发布长期记忆事件（异步，非阻塞） =====
        eventPublisher.publishEvent(new MemoryPersistEvent(this, sessionId, "ASSISTANT", answer));

        return answer;
    }

    /**
     * 处理闲聊意图
     * <p>
     * 直接由 LLM 回复，不走检索或数据库查询。
     * <p>
     * ====================================================================
     * 【学习要点: 长期记忆补充 —— 解决滑动窗口遗忘问题】
     * ====================================================================
     * 问题: 滑动窗口只保留最近10条消息。如果用户对话了50轮，第51轮问
     *      "第一轮我说了什么名字？"，短期记忆中没有第一轮的消息。
     * <p>
     * 解决: 两层记忆协作
     * - Layer 1 工作记忆（Redis 滑动窗口）: 最近10条消息 → 作为 messages 注入
     * - Layer 2 画像记忆（结构化事实）: user_name=张三 等 → 作为 system message 注入，永不丢失
     * <p>
     * 效果: 即使2000轮对话后，AI 仍然记得第一轮中用户说的名字（画像记忆永不压缩）。
     */
    private String handleChat(String sessionId, String question) {
        log.info("【闲聊路由】直接回复");

        // 获取短期记忆（从 Redis 滑动窗口中读取最近10条）
        List<Message> history = shortTermMemory.get(sessionId);

        // 获取长期记忆上下文（画像记忆：结构化事实）
        String longTermContext = buildLongTermContext(sessionId);

        if (longTermContext != null && !longTermContext.isBlank()) {
            return chatClient.prompt()
                .system(longTermContext)   // 注入长期记忆作为系统消息
                .messages(history)         // 注入短期记忆（最近10条）
                .user(question)
                .call()
                .content();
        }

        return chatClient.prompt()
            .messages(history)
            .user(question)
            .call()
            .content();
    }

    /**
     * 处理 RAG 知识库查询
     * <p>
     * 完整 RAG 链路: Query改写 → 多路召回 → 上下文注入 → LLM生成
     * <p>
     * 【学习要点: RAG 核心步骤】
     * 1. Query改写: 优化查询，提高召回率
     * 2. 多路召回: 用多个查询版本从向量库检索相关文档
     * 3. 上下文构建: 将检索到的文档拼接为上下文
     * 4. LLM生成: 将上下文 + 用户问题 注入 Prompt，让 LLM 基于真实数据回答
     */
    private String handleRagQuery(String sessionId, String question) {
        log.info("【RAG路由】开始 RAG 全链路处理");

        // Step 1: Query改写
        QueryRewriteResult rewriteResult = queryRewriter.rewrite(question);
        log.info("【RAG-Step1】Query改写完成: {} 个改写版本, {} 个子查询",
            rewriteResult.rewrittenQueries().size(),
            rewriteResult.subQueries().size());

        // Step 2: 多路召回
        RetrievedContext context = documentRetriever.multiWayRetrieve(question, rewriteResult);
        log.info("【RAG-Step2】多路召回完成: 召回 {} 篇文档", context.totalFound());

        // Step 3: 基于检索上下文生成回答
        if (context.documents().isEmpty()) {
            log.warn("【RAG-Step3】未召回任何文档");
            return "抱歉，我在知识库中没有找到与您问题相关的信息。请尝试更具体地描述您的问题，或联系客服人员获取帮助。";
        }

        return buildRagResponse(sessionId, question, context);
    }

    /**
     * 处理数据库查询
     * <p>
     * NL2SQL 链路: 自然语言 → SQL生成 → 执行查询 → 结果解读
     */
    private String handleDbQuery(String sessionId, String question) {
        log.info("【DB路由】开始 NL2SQL 数据库查询");

        // Step 1: NL2SQL + 执行查询
        String queryResult = databaseQueryService.queryByNaturalLanguage(question);
        log.info("【DB-Step1】查询结果: {} 字符", queryResult.length());

        // Step 2: 让 LLM 解读查询结果
        return buildDbQueryResponse(sessionId, question, queryResult);
    }

    /**
     * 构建 RAG 回答
     * <p>
     * 将检索到的文档上下文注入 Prompt，让 LLM 基于真实数据生成回答。
     * 同时注入长期记忆，确保 LLM 了解用户的历史背景。
     */
    private String buildRagResponse(String sessionId, String question, RetrievedContext context) {
        // 拼接所有检索到的文档
        String contextStr = String.join("\n---\n", context.documents());
    
        String prompt = PromptTemplates.RAG_ANSWER
            .replace("{context}", contextStr)
            .replace("{question}", question);
    
        // 注入长期记忆上下文
        String longTermContext = buildLongTermContext(sessionId);
        if (longTermContext != null && !longTermContext.isBlank()) {
            return chatClient.prompt()
                .system(longTermContext)
                .user(prompt)
                .call()
                .content();
        }
    
        String response = chatClient.prompt()
            .user(prompt)
            .call()
            .content();
    
        log.info("【RAG完成】回答长度: {} 字符", response.length());
        return response;
    }

    /**
     * 构建数据库查询回答
     * <p>
     * 将 SQL 查询结果传回 LLM，让 LLM 将结构化数据转化为自然语言回答。
     * 同时注入长期记忆，确保 LLM 了解用户的历史背景。
     */
    private String buildDbQueryResponse(String sessionId, String question, String queryResult) {
        String prompt = PromptTemplates.DB_QUERY_ANSWER
            .replace("{question}", question)
            .replace("{result}", queryResult);

        // 注入长期记忆上下文
        String longTermContext = buildLongTermContext(sessionId);
        if (longTermContext != null && !longTermContext.isBlank()) {
            return chatClient.prompt()
                .system(longTermContext)
                .user(prompt)
                .call()
                .content();
        }

        String response = chatClient.prompt()
            .user(prompt)
            .call()
            .content();

        log.info("【DB查询完成】回答长度: {} 字符", response.length());
        return response;
    }

    /**
     * 带文档类型过滤的 RAG 查询
     * <p>
     * 当明确知道问题属于某类文档时使用，可提高检索精度。
     * 例如："退货政策" 类文档只包含退货相关内容。
     */
    public String chatWithDocTypeFilter(String question, String docType) {
        return chatWithDocTypeFilter(null, question, docType);
    }

    /**
     * 带会话ID和文档类型过滤的 RAG 查询
     */
    public String chatWithDocTypeFilter(String sessionId, String question, String docType) {
        log.info("【RAG-过滤模式】问题: {}, 文档类型: {}", question, docType);

        RetrievedContext context = documentRetriever.retrieveWithFilter(question, docType);

        if (context.documents().isEmpty()) {
            return "在 " + docType + " 类文档中未找到相关信息。";
        }

        return buildRagResponse(sessionId, question, context);
    }

    // ========================
    // 长期记忆检索
    // ========================

    /**
     * 构建长期记忆上下文（两层架构：只用画像记忆）
     * <p>
     * ====================================================================
     * 【企业级设计: 两层记忆，精简版】
     * ====================================================================
     * <pre>
     * 精简后只保留两层:
     *   Layer 1: 工作记忆（Redis 滑动窗口）→ messages() 注入
     *   Layer 2: 画像记忆（结构化事实）→ system() 注入
     *
     * 为什么可以删掉情景层（滚动摘要）:
     *   - "用户叫张三" → 画像层已有 user_name=张三 ✅
     *   - "讨论过退货" → 画像层已有 topic=退货政策 ✅
     *   - "确认了7天退货" → 画像层已有 decision=已确认7天无理由 ✅
     *   - 画像层已覆盖情景层能提供的全部关键信息
     *   - 情景层的"叙述性摘要"只会增加 token，不提供增量价值
     *
     * 注入的 token 量:
     *   画像文本: ~200 token（最多30条事实，每条约7 token）
     *   恒定不变，不随对话轮数增长
     * </pre>
     * <p>
     * 按需检索: 短期窗口内（≤5条）的对话不查画像
     * 容错设计: 查询失败 → 降级为无记忆模式
     *
     * @param sessionId 会话ID
     * @return 画像记忆文本，无事实时返回 null
     */
    private String buildLongTermContext(String sessionId) {
        try {
            // 按需检索: 对话刚开始时不需要查画像
            List<Message> currentWindow = shortTermMemory.get(sessionId);
            if (currentWindow == null || currentWindow.size() < 6) {
                return null;  // 前几轮对话，短期记忆足够
            }

            // 只查画像记忆（Layer 2: 结构化事实，永不压缩）
            String profileText = factExtractor.getProfileText(sessionId);
            if (profileText != null) {
                log.debug("【画像记忆】注入: {} 字符", profileText.length());
            }
            return profileText;

        } catch (Exception e) {
            log.warn("【画像记忆】查询失败，降级: {}", e.getMessage());
            return null;
        }
    }
}
