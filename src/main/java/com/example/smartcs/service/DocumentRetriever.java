package com.example.smartcs.service;

import com.example.smartcs.model.QueryRewriteResult;
import com.example.smartcs.model.RetrievedContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 文档检索服务 - 多路召回 + 元数据过滤
 * <p>
 * ========================================================================
 * 【学习要点: 多路召回】
 * ========================================================================
 * 多路召回是指使用多种检索策略同时搜索，然后合并结果，以提高召回率。
 * <p>
 * 常见的召回策略：
 * 1. 向量检索 (Dense Retrieval): 基于语义相似度，能匹配同义词/近义表述
 * 2. 关键词检索 (Sparse/BM25): 基于关键词精确匹配，适合专有名词/编号
 * 3. 元数据过滤: 基于文档属性过滤（如文档类型、来源等）
 * <p>
 * 本实现的多路召回策略：
 * - 路径1: 原始查询 → 向量检索
 * - 路径2: 改写查询 → 向量检索（每个改写版本独立检索）
 * - 路径3: 子查询 → 向量检索（分解后的子问题独立检索）
 * - 合并: 分层级联去重（ID去重 → 精确去重 → Jaccard相似度去重）
 * <p>
 * ========================================================================
 * 【学习要点: 元数据过滤】
 * ========================================================================
 * 元数据过滤是在向量检索的基础上，增加结构化的过滤条件。
 * 例如：只在"退货政策"类文档中搜索，或只搜索特定来源的文档。
 * <p>
 * Spring AI 提供了 FilterExpressionBuilder，支持类似SQL的过滤语法：
 * - eq("key", "value"): 等于
 * - in("key", "v1", "v2"): 包含
 * - and/or/not: 逻辑组合
 */
@Slf4j
@Service
public class DocumentRetriever {

    private final VectorStore vectorStore;

    /** 默认返回的文档数量 */
    private static final int DEFAULT_TOP_K = 5;
    /** 相似度阈值: 低于此值的结果会被过滤 */
    private static final double SIMILARITY_THRESHOLD = 0.3;
    /** 触发激进去重的文档数阈值: 精确去重后超过此数量才启用Jaccard相似度去重 */
    private static final int AGGRESSIVE_DEDUP_THRESHOLD = 8;
    /** Jaccard相似度阈值: 超过此值视为重复 */
    private static final double JACCARD_THRESHOLD = 0.8;
    /** n-gram大小: 中文推荐2~4 */
    private static final int NGRAM_SIZE = 3;

    public DocumentRetriever(VectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    /**
     * 多路召回: 使用原始查询 + 改写查询 + 子查询 进行检索
     * <p>
     * 这是核心的检索方法，综合多种策略最大化召回率。
     *
     * @param query 原始查询
     * @param rewriteResult Query改写结果（包含改写版本和子查询）
     * @return 合并去重后的检索上下文
     */
    public RetrievedContext multiWayRetrieve(String query, QueryRewriteResult rewriteResult) {
        log.info("【多路召回】开始检索，原始查询: {}", query);

        // 使用 LinkedHashMap 去重（key=文档ID）并保持插入顺序
        Map<String, Document> allDocs = new LinkedHashMap<>();

        // ============ 路径1: 原始查询向量检索 ============
        List<Document> originalDocs = vectorSearch(query, DEFAULT_TOP_K, null);
        originalDocs.forEach(doc -> allDocs.putIfAbsent(doc.getId(), doc));
        log.info("【多路召回-路径1】原始查询召回 {} 篇文档", originalDocs.size());

        // ============ 路径2: 改写查询向量检索 ============
        if (rewriteResult != null && rewriteResult.rewrittenQueries() != null) {
            for (String rewritten : rewriteResult.rewrittenQueries()) {
                if (!rewritten.equals(query)) {
                    List<Document> docs = vectorSearch(rewritten, 3, null);
                    docs.forEach(doc -> allDocs.putIfAbsent(doc.getId(), doc));
                    log.debug("【多路召回-路径2】改写查询'{}'召回 {} 篇文档", rewritten, docs.size());
                }
            }
        }

        // ============ 路径3: 子查询向量检索 ============
        if (rewriteResult != null && rewriteResult.subQueries() != null) {
            for (String subQuery : rewriteResult.subQueries()) {
                List<Document> docs = vectorSearch(subQuery, 3, null);
                docs.forEach(doc -> allDocs.putIfAbsent(doc.getId(), doc));
                log.debug("【多路召回-路径3】子查询'{}'召回 {} 篇文档", subQuery, docs.size());
            }
        }

        // ================================================================
        // 【分层级联去重】
        // ================================================================
        // 策略: 先跑便宜的，再跑贵的；每层都在上一层的结果上继续过滤。
        //
        //   原始召回 → 第1层:ID去重 → 第2层:内容精确去重 → [条件触发] 第3层:Jaccard相似度去重
        //
        // 触发条件: 第2层去重后文档数仍超过 AGGRESSIVE_DEDUP_THRESHOLD 时才启用第3层，
        //          避免小查询浪费 O(n²) 算力。
        // ================================================================

        // --- 第1层: ID去重 ---
        // LinkedHashMap 的 putIfAbsent 已在召回阶段完成，直接取值。
        // 成本: O(1)，始终执行。
        List<Document> deduped = new ArrayList<>(allDocs.values());
        int afterIdDedup = deduped.size();

        // --- 第2层: 内容精确去重 ---
        // 空白归一化 + Set判重，过滤不同ID但内容完全相同的文档。
        // 成本: O(n)，始终执行。
        deduped = deduplicateExact(deduped);
        log.info("【级联去重】ID去重: {} → 精确去重: {}", afterIdDedup, deduped.size());

        // --- 第3层: Jaccard相似度去重（条件触发）---
        // 仅在文档数较多时启用，用于捕获近似重复（微调几个字、增删句子等）。
        // 成本: O(n² * L)，仅在文档数 > AGGRESSIVE_DEDUP_THRESHOLD 时执行。
        if (deduped.size() > AGGRESSIVE_DEDUP_THRESHOLD) {
            int beforeJaccard = deduped.size();
            deduped = deduplicateByJaccard(deduped, JACCARD_THRESHOLD, NGRAM_SIZE);
            log.info("【级联去重】Jaccard相似度去重: {} → {}", beforeJaccard, deduped.size());
        }

        List<String> docTexts = deduped.stream()
            .map(Document::getText)
            .collect(Collectors.toList());

        log.info("【多路召回】最终召回 {} 篇文档", docTexts.size());
        return new RetrievedContext(docTexts, docTexts.size(), "多路召回(原始+改写+子查询)");
    }

    /**
     * 带元数据过滤的检索
     * <p>
     * 当需要在特定类型的文档中搜索时使用。
     * 例如：只在 FAQ 文档中搜索，或只在退货政策中搜索。
     *
     * @param query 查询文本
     * @param docType 文档类型过滤条件（null 表示不过滤）
     * @return 检索结果
     */
    public RetrievedContext retrieveWithFilter(String query, String docType) {
        log.info("【元数据过滤检索】查询: {}, 文档类型: {}", query, docType);

        Filter.Expression filter = null;
        if (docType != null && !docType.isEmpty()) {
            // 使用 FilterExpressionBuilder 构建过滤表达式
            FilterExpressionBuilder builder = new FilterExpressionBuilder();
            // 只搜索指定文档类型的文档
            filter = builder.eq("docType", docType).build();
        }

        List<Document> docs = vectorSearch(query, DEFAULT_TOP_K, filter);

        List<String> docTexts = docs.stream()
            .map(doc -> String.format("[来源:%s, 类型:%s]\n%s",
                doc.getMetadata().getOrDefault("source", "未知"),
                doc.getMetadata().getOrDefault("docType", "未知"),
                doc.getText()))
            .collect(Collectors.toList());

        return new RetrievedContext(docTexts, docTexts.size(),
            "元数据过滤检索(type=" + docType + ")");
    }

    // ========================
    // 级联去重 - 各层实现方法
    // ========================

    /**
     * 第2层: 内容精确去重
     * <p>
     * 对文档文本做空白归一化后，用Set判断是否已出现过。
     * 能捕获"不同ID但内容完全相同"的重复文档（如同一文档被多次导入向量库）。
     * <p>
     * 成本: O(n)，始终执行。
     *
     * @param docs ID去重后的文档列表
     * @return 精确去重后的文档列表
     */
    private List<Document> deduplicateExact(List<Document> docs) {
        Set<String> seen = new HashSet<>();
        return docs.stream()
            .filter(doc -> {
                String normalized = doc.getText().replaceAll("\\s+", "").trim();
                return seen.add(normalized);
            })
            .collect(Collectors.toList());
    }

    /**
     * 第3层备选: 子串包含去重
     * <p>
     * 两两比较文档内容，如果一个片段被另一个更长的片段完全包含，
     * 则丢弃短的那个（保留信息更完整的长片段）。
     * <p>
     * 能处理chunk overlap导致的重叠问题。当前级联流水线未默认启用，
     * 如需使用可在级联流水线中替换 deduplicateByJaccard 或在其之前调用。
     * <p>
     * 成本: O(n²)。
     *
     * @param docs 上一层去重后的文档列表
     * @return 子串去重后的文档列表
     */
    private List<Document> deduplicateByContainment(List<Document> docs) {
        List<String> normalizedTexts = docs.stream()
            .map(doc -> doc.getText().replaceAll("\\s+", "").trim())
            .collect(Collectors.toList());

        List<Document> result = new ArrayList<>();
        for (int i = 0; i < docs.size(); i++) {
            String current = normalizedTexts.get(i);
            boolean isDuplicate = false;
            for (int j = 0; j < normalizedTexts.size(); j++) {
                if (i == j) continue;
                String other = normalizedTexts.get(j);
                // 完全相同: 保留先出现的（索引更小的）
                if (current.equals(other) && i > j) {
                    isDuplicate = true;
                    break;
                }
                // 子串包含: 当前文本被另一段包含，丢弃短的
                if (!current.equals(other) && other.contains(current)) {
                    isDuplicate = true;
                    break;
                }
            }
            if (!isDuplicate) {
                result.add(docs.get(i));
            }
        }
        return result;
    }

    /**
     * 第3层: Jaccard相似度去重
     * <p>
     * 将每段文本按n-gram切分为字符集合，计算两两之间的Jaccard系数。
     * 超过阈值则认为高度相似，丢弃后出现的那个。
     * <p>
     * 能检测"近似但不完全相同"的重复（微调几个字、增删句子等），
     * 是工业界常用的文本去重算法。
     * <p>
     * 成本: O(n² * L)，仅在文档数较多时由级联流水线触发。
     *
     * @param docs      上一层去重后的文档列表
     * @param threshold Jaccard相似度阈值，超过此值视为重复
     * @param ngramSize n-gram大小
     * @return 相似度去重后的文档列表
     */
    private List<Document> deduplicateByJaccard(List<Document> docs, double threshold, int ngramSize) {
        // 预计算所有文档的n-gram集合，避免重复计算
        List<Set<String>> ngramSets = docs.stream()
            .map(doc -> buildNgramSet(doc.getText(), ngramSize))
            .collect(Collectors.toList());

        List<Document> result = new ArrayList<>();
        for (int i = 0; i < docs.size(); i++) {
            boolean isDuplicate = false;
            for (int j = 0; j < result.size(); j++) {
                // 找到result中第j个文档对应的原始索引
                int keptIndex = -1;
                for (int k = 0; k < i; k++) {
                    if (docs.get(k).getText().equals(result.get(j).getText())) {
                        keptIndex = k;
                        break;
                    }
                }
                if (keptIndex >= 0) {
                    double similarity = jaccardSimilarity(ngramSets.get(i), ngramSets.get(keptIndex));
                    if (similarity >= threshold) {
                        log.debug("【Jaccard去重】文档[{}]与已保留文档[{}]相似度={}, 判定为重复",
                            i, keptIndex, String.format("%.2f", similarity));
                        isDuplicate = true;
                        break;
                    }
                }
            }
            if (!isDuplicate) {
                result.add(docs.get(i));
            }
        }
        return result;
    }

    // ========================
    // 工具方法
    // ========================

    /**
     * 计算两个集合的Jaccard相似度
     * <p>
     * 公式: Jaccard(A, B) = |A ∩ B| / |A ∪ B|
     * 值域: [0, 1]，0表示完全不同，1表示完全相同。
     *
     * @param setA 集合A
     * @param setB 集合B
     * @return Jaccard相似度系数
     */
    private double jaccardSimilarity(Set<String> setA, Set<String> setB) {
        if (setA.isEmpty() && setB.isEmpty()) return 1.0;
        if (setA.isEmpty() || setB.isEmpty()) return 0.0;

        // 计算交集大小（不修改原集合）
        long intersectionSize = setA.stream().filter(setB::contains).count();
        // 并集大小 = |A| + |B| - |A ∩ B|
        long unionSize = (long) setA.size() + setB.size() - intersectionSize;

        return (double) intersectionSize / unionSize;
    }

    /**
     * 将文本切分为n-gram字符集合
     * <p>
     * 例如文本"你好世界"，n=2时生成 {"你好", "好世", "世界"}。
     * n-gram是文本相似度计算中常用的特征提取方式：
     * - n越小，粒度越细，对微小差异更敏感
     * - n越大，粒度越粗，更能反映句子级别的结构相似性
     * - 中文推荐n=2~4，英文推荐n=3~5
     *
     * @param text 输入文本
     * @param n    n-gram大小
     * @return n-gram字符串集合
     */
    private Set<String> buildNgramSet(String text, int n) {
        // 先做空白归一化，消除格式差异
        String normalized = text.replaceAll("\\s+", "");
        Set<String> ngrams = new HashSet<>();
        for (int i = 0; i <= normalized.length() - n; i++) {
            ngrams.add(normalized.substring(i, i + n));
        }
        return ngrams;
    }

    /**
     * 底层向量检索方法
     * <p>
     * 封装了 PgVectorStore 的 similaritySearch 调用。
     * <p>
     * 工作原理：
     * 1. 将查询文本通过 EmbeddingModel 转换为向量
     * 2. 在 PgVector 中计算余弦相似度
     * 3. 返回 TopK 最相似的结果
     *
     * @param query 查询文本
     * @param topK 返回结果数量
     * @param filter 元数据过滤表达式（可为null）
     * @return 匹配的文档列表
     */
    private List<Document> vectorSearch(String query, int topK, Filter.Expression filter) {
        try {
            SearchRequest.Builder requestBuilder = SearchRequest.builder()
                .query(query)
                .topK(topK)
                .similarityThreshold(SIMILARITY_THRESHOLD);

            if (filter != null) {
                requestBuilder.filterExpression(filter);
            }

            return vectorStore.similaritySearch(requestBuilder.build());

        } catch (Exception e) {
            log.error("【向量检索】失败: {}", e.getMessage());
            return List.of();
        }
    }
}
