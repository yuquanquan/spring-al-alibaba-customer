package com.example.smartcs.service;

import com.example.smartcs.entity.UserFact;
import com.example.smartcs.repository.UserFactRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 事实提取服务（画像记忆 Layer 2 的核心）
 * <p>
 * ========================================================================
 * 【企业级设计: 结构化事实提取，替代有损压缩】
 * ========================================================================
 * <p>
 * 传统方案（滚动摘要）的问题：
 * <pre>
 *   "用户叫张三" → "用户咨询过" → "用户是活跃客户" → 信息丢失💀
 *   有损压缩叠加 = JPEG反复保存 = 越压越糊
 * </pre>
 * <p>
 * 本方案（结构化事实）：
 * <pre>
 *   对话中出现 "我叫张三" → 提取 fact: user_name=张三
 *   100轮后仍然记得: user_name=张三 ✅
 *   2000轮后仍然记得: user_name=张三 ✅
 *   事实是 UPSERT 的，不是压缩的，永远不丢失
 * </pre>
 * <p>
 * 工作原理：
 * 1. 每次压缩触发时，将最近的对话 + 已有事实 交给 LLM
 * 2. LLM 返回 JSON 格式的新事实列表
 * 3. 对每个事实执行 UPSERT（同key更新，新key插入）
 * 4. 事实总数上限 MAX_FACTS=30，超限时淘汰低重要性事实
 */
@Slf4j
@Service
public class FactExtractor {

    @Autowired
    private ChatClient chatClient;

    @Autowired
    private UserFactRepository factRepo;

    @Autowired
    private StringRedisTemplate redisTemplate;

    /** Redis Hash 缓存 key 前缀 */
    private static final String PROFILE_KEY_PREFIX = "chat:profile:";

    /** Redis 缓存 TTL（小时） */
    private static final long PROFILE_TTL_HOURS = 24;

    /** 事实总数上限（超过后淘汰低重要性的） */
    private static final int MAX_FACTS = 30;

    /**
     * 从对话片段中提取结构化事实，并 UPSERT 到画像记忆
     *
     * @param sessionId      会话ID
     * @param conversation   最近的对话文本（未压缩的消息）
     */
    @Transactional
    public void extractAndSaveFacts(String sessionId, String conversation) {
        try {
            // 1. 获取已有事实（让 LLM 知道哪些需要更新）
            List<UserFact> existingFacts = factRepo.findBySessionId(sessionId);
            String existingFactsText = existingFacts.isEmpty()
                ? "（暂无）"
                : existingFacts.stream()
                    .map(f -> f.getFactKey() + " = " + f.getFactValue())
                    .reduce((a, b) -> a + "\n" + b)
                    .orElse("（暂无）");

            // 2. 调用 LLM 提取事实
            String jsonResult = extractFactsWithLLM(conversation, existingFactsText);

            // 3. 解析 JSON 并 UPSERT
            List<ExtractedFact> extracted = parseFacts(jsonResult);
            for (ExtractedFact ef : extracted) {
                upsertFact(sessionId, ef);
            }

            // 4. 淘汰超限的低重要性事实
            pruneFactsIfNeeded(sessionId);

            // 5. 同步到 Redis Hash 缓存（HSET 天然 UPSERT）
            syncFactsToRedis(sessionId, factRepo.findBySessionId(sessionId));

            log.info("【画像记忆】提取 {} 条事实，当前总数: {}",
                extracted.size(), factRepo.countBySessionId(sessionId));

        } catch (Exception e) {
            // 事实提取失败不影响主流程（降级为无画像模式）
            log.warn("【画像记忆】事实提取失败: {}", e.getMessage());
        }
    }

    /**
     * 获取会话的所有画像事实（供 buildLongTermContext 使用）
     * <p>
     * 优先从 Redis Hash 读取（< 1ms），Redis 无数据时回查 DB。
     *
     * @param sessionId 会话ID
     * @return 格式化的事实文本
     */
    public String getProfileText(String sessionId) {
        // 优先读 Redis 缓存
        try {
            String redisKey = PROFILE_KEY_PREFIX + sessionId;
            Map<Object, Object> cached = redisTemplate.opsForHash().entries(redisKey);
            if (cached != null && !cached.isEmpty()) {
                StringBuilder sb = new StringBuilder("【用户画像】\n");
                cached.forEach((k, v) -> sb.append("- ").append(k).append(": ").append(v).append("\n"));
                return sb.toString().trim();
            }
        } catch (Exception e) {
            log.warn("【画像缓存】Redis读取失败，回查DB: {}", e.getMessage());
        }

        // 回查 DB
        List<UserFact> facts = factRepo.findBySessionId(sessionId);
        if (facts.isEmpty()) {
            return null;
        }

        // 回写 Redis 缓存
        syncFactsToRedis(sessionId, facts);

        StringBuilder sb = new StringBuilder("【用户画像】\n");
        for (UserFact f : facts) {
            sb.append("- ").append(f.getFactKey()).append(": ")
              .append(f.getFactValue()).append("\n");
        }
        return sb.toString().trim();
    }

    /**
     * 清空会话的所有画像事实（同时清理 Redis 缓存）
     */
    public void clearFacts(String sessionId) {
        factRepo.deleteBySessionId(sessionId);
        try {
            redisTemplate.delete(PROFILE_KEY_PREFIX + sessionId);
        } catch (Exception e) {
            log.warn("【画像缓存】Redis清理失败: {}", e.getMessage());
        }
    }

    /**
     * 调用 LLM 从对话中提取结构化事实
     */
    private String extractFactsWithLLM(String conversation, String existingFacts) {
        String prompt = String.format("""
            你是一个信息提取专家。请从以下对话中提取关键事实信息。
            
            【已有事实】（如果新对话中有更新，请用相同 key 覆盖）:
            %s
            
            【新对话】:
            %s
            
            【提取规则】:
            1. 提取用户身份（姓名、邮箱、电话、角色等）→ category=PROFILE, importance=5
            2. 提取用户偏好（语言、风格、习惯等）→ category=PREFERENCE, importance=3
            3. 提取重要决策和结论 → category=DECISION, importance=4
            4. 提取关键业务实体（订单号、产品名等）→ category=ENTITY, importance=3
            5. 只提取明确的、确定的信息，不要推测
            6. key 使用英文 snake_case（如 user_name, order_id）
            7. 如果已有事实中的某个值在新对话中被更正了，用相同 key 输出新值
            8. 如果没有新的事实需要提取，返回空数组
            
            请以 JSON 数组格式返回，每个元素包含:
            {"key": "fact_key", "value": "fact_value", "category": "PROFILE", "importance": 5}
            
            只返回 JSON，不要其他文字。
            """, existingFacts, conversation);

        return chatClient.prompt()
            .user(prompt)
            .call()
            .content();
    }

    /**
     * 解析 LLM 返回的 JSON 事实列表
     * <p>
     * 使用简单的字符串解析，避免引入额外的 JSON 依赖。
     * 如果解析失败，返回空列表（不影响主流程）。
     */
    private List<ExtractedFact> parseFacts(String json) {
        List<ExtractedFact> facts = new java.util.ArrayList<>();
        try {
            // 简单解析 JSON 数组
            // 格式: [{"key":"...", "value":"...", "category":"...", "importance":N}, ...]
            String cleaned = json.trim();
            // 去掉可能的 markdown 代码块标记
            if (cleaned.startsWith("```")) {
                cleaned = cleaned.replaceAll("```[a-z]*\\n?", "").replaceAll("```", "").trim();
            }

            // 按 } 分割每个对象
            String[] items = cleaned.split("\\}");
            for (String item : items) {
                String key = extractJsonValue(item, "key");
                String value = extractJsonValue(item, "value");
                String category = extractJsonValue(item, "category");
                String importanceStr = extractJsonValue(item, "importance");

                if (key != null && value != null) {
                    int importance = 3; // 默认值
                    try {
                        importance = Integer.parseInt(importanceStr != null ? importanceStr : "3");
                    } catch (NumberFormatException ignored) {}

                    facts.add(new ExtractedFact(key, value,
                        category != null ? category : "ENTITY", importance));
                }
            }
        } catch (Exception e) {
            log.warn("【画像记忆】JSON解析失败: {}", e.getMessage());
        }
        return facts;
    }

    /**
     * 从 JSON 片段中提取指定字段的值
     */
    private String extractJsonValue(String json, String field) {
        // 匹配 "field": "value" 或 "field": number
        String pattern1 = "\"" + field + "\"\\s*:\\s*\"([^\"]+)\"";
        String pattern2 = "\"" + field + "\"\\s*:\\s*(\\d+)";

        java.util.regex.Matcher m1 = java.util.regex.Pattern.compile(pattern1).matcher(json);
        if (m1.find()) return m1.group(1);

        java.util.regex.Matcher m2 = java.util.regex.Pattern.compile(pattern2).matcher(json);
        if (m2.find()) return m2.group(1);

        return null;
    }

    /**
     * UPSERT 一条事实（同 key 更新，新 key 插入）
     */
    private void upsertFact(String sessionId, ExtractedFact ef) {
        var existing = factRepo.findBySessionIdAndFactKey(sessionId, ef.key);
        if (existing.isPresent()) {
            // 更新已有事实
            UserFact fact = existing.get();
            fact.setFactValue(ef.value);
            fact.setCategory(ef.category);
            fact.setImportance(ef.importance);
            fact.setUpdateTime(LocalDateTime.now());
            factRepo.save(fact);
            log.debug("【画像记忆】更新事实: {} = {}", ef.key, ef.value);
        } else {
            // 插入新事实
            UserFact fact = new UserFact();
            fact.setSessionId(sessionId);
            fact.setFactKey(ef.key);
            fact.setFactValue(ef.value);
            fact.setCategory(ef.category);
            fact.setImportance(ef.importance);
            fact.setCreateTime(LocalDateTime.now());
            fact.setUpdateTime(LocalDateTime.now());
            factRepo.save(fact);
            log.debug("【画像记忆】新增事实: {} = {}", ef.key, ef.value);
        }
    }

    /**
     * 淘汰超限的低重要性事实
     * <p>
     * 当事实总数超过 MAX_FACTS 时，删除重要性最低的事实。
     * 确保画像记忆大小恒定。
     */
    private void pruneFactsIfNeeded(String sessionId) {
        long count = factRepo.countBySessionId(sessionId);
        if (count <= MAX_FACTS) return;

        List<UserFact> all = factRepo.findBySessionId(sessionId); // 已按 importance DESC 排序
        // 保留前 MAX_FACTS 条，删除其余
        for (int i = MAX_FACTS; i < all.size(); i++) {
            factRepo.delete(all.get(i));
            log.debug("【画像记忆】淘汰低优先级事实: {}", all.get(i).getFactKey());
        }
    }

    /**
     * 内部 DTO: LLM 提取出的单条事实
     */
    private record ExtractedFact(String key, String value, String category, int importance) {}

    /**
     * 同步事实到 Redis Hash 缓存
     * <p>
     * Redis Hash 天然支持 UPSERT 语义：
     * HSET key field value → 同 field 覆盖，新 field 插入
     */
    private void syncFactsToRedis(String sessionId, List<UserFact> facts) {
        try {
            String redisKey = PROFILE_KEY_PREFIX + sessionId;
            // 先清空旧缓存，再全量写入（保持与 DB 一致）
            redisTemplate.delete(redisKey);
            if (!facts.isEmpty()) {
                Map<String, String> map = facts.stream()
                    .collect(Collectors.toMap(
                        UserFact::getFactKey, UserFact::getFactValue, (a, b) -> b));
                redisTemplate.opsForHash().putAll(redisKey, map);
                redisTemplate.expire(redisKey, PROFILE_TTL_HOURS, TimeUnit.HOURS);
            }
        } catch (Exception e) {
            log.warn("【画像缓存】Redis同步失败: {}", e.getMessage());
        }
    }
}
