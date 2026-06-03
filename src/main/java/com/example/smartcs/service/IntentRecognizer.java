package com.example.smartcs.service;

import com.example.smartcs.config.PromptTemplates;
import com.example.smartcs.model.ChatIntent;
import com.example.smartcs.model.IntentType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.stereotype.Service;

/**
 * 意图识别服务
 * <p>
 * ========================================================================
 * 【学习要点: 意图识别】
 * ========================================================================
 * 意图识别是智能客服系统的"第一道关卡"，决定用户请求走哪条处理链路。
 * <p>
 * 本实现采用 LLM-Based 意图识别方案（相比传统 NLU 方案更灵活）：
 * <p>
 * 1. 输入: 用户原始文本
 * 2. 处理: 将文本 + 意图定义 + 示例 注入 Prompt
 * 3. 输出: 结构化 JSON → 自动解析为 ChatIntent 对象
 * <p>
 * 优势:
 * - 无需训练模型，通过修改 Prompt 即可调整意图分类
 * - 支持模糊匹配和上下文理解
 * - 自带置信度和推理过程（可解释性）
 * <p>
 * 局限:
 * - 每次调用都有 LLM 延迟（~500ms~2s）
 * - 生产环境建议加缓存（相同/相似查询命中缓存）
 */
@Slf4j
@Service
public class IntentRecognizer {

    private final ChatModel chatModel;
    private final BeanOutputConverter<ChatIntent> outputParser;

    public IntentRecognizer(ChatModel chatModel, BeanOutputConverter<ChatIntent> outputParser) {
        this.chatModel = chatModel;
        this.outputParser = outputParser;
    }

    /**
     * 识别用户输入的意图
     * <p>
     * 流程:
     * 1. 构建 Prompt（用户输入 + 意图定义 + 输出格式约束）
     * 2. 调用 LLM 获取 JSON 响应
     * 3. 使用 BeanOutputConverter 自动转换为 ChatIntent
     *
     * @param userInput 用户输入的原始文本
     * @return 意图识别结果（包含意图类型、置信度、推理过程）
     */
    public ChatIntent recognize(String userInput) {
        log.info("【意图识别】开始分析用户输入: {}", userInput);

        try {
            // 步骤1: 构建 ChatClient（不继承默认系统提示词，意图识别需要独立的 Prompt）
            ChatClient client = ChatClient.builder(chatModel).build();

            // 步骤2: 构建 Prompt
            // outputParser.getFormat() 会生成类似以下格式的指令:
            // "Your response should be in JSON format. The JSON schema is as follows: ..."
            String prompt = PromptTemplates.INTENT_RECOGNITION
                .replace("{userInput}", userInput)
                .replace("{format}", outputParser.getFormat());

            // 步骤3: 调用 LLM
            String response = client.prompt()
                .user(prompt)
                .call()
                .content();

            log.debug("【意图识别】LLM原始响应: {}", response);

            // 步骤4: 解析响应为结构化对象
            ChatIntent intent = outputParser.convert(response);

            log.info("【意图识别】结果: type={}, confidence={}, reason={}",
                intent.intentType(), intent.confidence(), intent.reason());

            return intent;

        } catch (Exception e) {
            // 降级策略: 意图识别失败时，默认走 DB_QUERY
            // 生产环境中可以根据业务场景选择更合适的降级策略
            log.error("【意图识别】失败，降级为DB_QUERY: {}", e.getMessage());
            return new ChatIntent(IntentType.DB_QUERY, 0.0, "意图识别异常，使用默认路由");
        }
    }

    /**
     * 快速判断是否为闲聊意图
     * <p>
     * 基于关键词的轻量级判断，用于绕过 LLM 调用，降低延迟。
     * 适用于明确是闲聊的场景（如"你好"、"谢谢"等）。
     */
    public boolean isLikelyChat(String input) {
        String lower = input.toLowerCase().trim();
        String[] chatKeywords = {"你好", "hello", "hi", "嗨", "谢谢", "再见", "拜拜", "好的", "嗯"};
        for (String keyword : chatKeywords) {
            if (lower.equals(keyword) || lower.startsWith(keyword)) {
                return true;
            }
        }
        // 极短输入大概率是闲聊
        return lower.length() <= 3;
    }
}
