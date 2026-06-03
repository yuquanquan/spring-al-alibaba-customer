package com.example.smartcs.service;

import com.example.smartcs.config.PromptTemplates;
import com.example.smartcs.model.QueryRewriteResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Query改写服务
 * <p>
 * ========================================================================
 * 【学习要点: Query改写】
 * ========================================================================
 * Query改写是 RAG 链路中提升"召回率"的核心技术。
 * <p>
 * 问题背景: 用户查询可能存在以下问题导致检索不到相关文档：
 * - 表述模糊: "那个怎么弄" → 语义不明确
 * - 口语化: "咋退钱" → 知识库中写的是"退货退款流程"
 * - 复合问题: "功能和价格" → 一个向量无法同时匹配两个主题
 * <p>
 * 改写策略:
 * 1. 语义保持改写: 换表述方式，保持语义不变（提升向量匹配的覆盖面）
 * 2. 关键词扩展: 添加同义词/专业术语（提升关键词命中率）
 * 3. 查询分解: 复合问题拆分为子问题（每个子问题独立检索）
 * <p>
 * 多版本查询 + 多路召回 = 更高的召回率
 */
@Slf4j
@Service
public class QueryRewriter {

    private final ChatModel chatModel;

    public QueryRewriter(ChatModel chatModel) {
        this.chatModel = chatModel;
    }

    /**
     * 对用户查询进行多维度改写
     *
     * @param originalQuery 原始查询
     * @return 改写结果（包含改写版本和子查询）
     */
    public QueryRewriteResult rewrite(String originalQuery) {
        log.info("【Query改写】原始查询: {}", originalQuery);

        try {
            ChatClient client = ChatClient.builder(chatModel).build();

            String prompt = PromptTemplates.QUERY_REWRITE
                .replace("{userQuery}", originalQuery);

            String response = client.prompt()
                .user(prompt)
                .call()
                .content();

            log.debug("【Query改写】LLM原始响应: {}", response);

            // 解析JSON响应
            return parseRewriteResponse(response, originalQuery);

        } catch (Exception e) {
            log.error("【Query改写】失败: {}", e.getMessage());
            // 降级: 返回原始查询，至少保证基础检索
            return new QueryRewriteResult(originalQuery, List.of(originalQuery), List.of());
        }
    }

    /**
     * 简单的JSON解析（生产环境建议使用 Jackson ObjectMapper）
     * <p>
     * 这里手动解析是为了学习目的，展示 LLM 返回的原始数据格式。
     * 实际项目中应使用结构化输出（BeanOutputParser）或 Jackson。
     */
    private QueryRewriteResult parseRewriteResponse(String response, String originalQuery) {
        try {
            // 清理响应中的代码块标记（LLM 有时会包裹 ```json ... ```）
            String cleaned = response
                .replaceAll("```json\\s*", "")
                .replaceAll("```\\s*", "")
                .trim();

            List<String> rewritten = extractJsonArray(cleaned, "rewritten");
            List<String> subQueries = extractJsonArray(cleaned, "subQueries");

            // 确保至少有一个改写版本
            if (rewritten.isEmpty()) {
                rewritten.add(originalQuery);
            }

            log.info("【Query改写】改写版本: {}, 子查询: {}", rewritten, subQueries);
            return new QueryRewriteResult(originalQuery, rewritten, subQueries);

        } catch (Exception e) {
            log.warn("【Query改写】JSON解析失败，使用原始查询: {}", e.getMessage());
            return new QueryRewriteResult(originalQuery, List.of(originalQuery), List.of());
        }
    }

    /**
     * 从JSON字符串中提取数组字段
     * 这是一个简单的正则提取实现，仅供学习参考
     */
    private List<String> extractJsonArray(String json, String fieldName) {
        List<String> result = new ArrayList<>();
        try {
            // 定位字段: "fieldName": [...]
            String pattern = "\"" + fieldName + "\"\\s*:\\s*\\[([^\\]]*)]";
            java.util.regex.Matcher matcher = java.util.regex.Pattern.compile(pattern).matcher(json);
            if (matcher.find()) {
                String arrayContent = matcher.group(1);
                // 提取数组中的字符串元素
                java.util.regex.Matcher elementMatcher =
                    java.util.regex.Pattern.compile("\"([^\"]+)\"").matcher(arrayContent);
                while (elementMatcher.find()) {
                    result.add(elementMatcher.group(1));
                }
            }
        } catch (Exception e) {
            log.debug("提取JSON数组失败: {}", e.getMessage());
        }
        return result;
    }
}
