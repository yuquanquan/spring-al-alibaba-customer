package com.example.smartcs.service;

import com.example.smartcs.model.QueryRewriteResult;
import com.example.smartcs.model.RetrievedContext;
import com.example.smartcs.search.HybridSearchService;
import com.example.smartcs.search.RerankService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 文档检索服务 - 多路召回 + 分数追踪 + 自适应重排序
 * <p>
 * ========================================================================
 * 【学习要点: 多路召回 + 分数透传 + 自适应重排序】
 * ========================================================================
 * <p>
 * 完整链路（改造后）：
 * <pre>
 *   Query改写
 *     ↓
 *   多路混合检索（原始 + 改写 + 子查询）
 *     ↓  每路内部: 向量+BM25 → RRF融合 → RRF分数存入 metadata
 *   分数累加合并（同一文档出现在多路 → 分数相加）
 *     ↓
 *   三级级联去重（ID → 精确 → Jaccard）
 *     ↓
 *   自适应排序:
 *     知识库 < 500篇 → 按 RRF 累加分数排序（零成本）
 *     知识库 ≥ 500篇 → 调 DashScope Rerank 模型精排（~200ms）
 *     ↓
 *   Parent-Child 上下文扩展
 *     ↓
 *   返回 TopK
 * </pre>
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
    private final HybridSearchService hybridSearchService;
    private final RerankService rerankService;

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

    /**
     * 启用 Rerank 模型的知识库文档数阈值
     * <p>
     * 知识库 < 此值 → 用 RRF 累加分数排序（零 API 调用，足够用）
     * 知识库 ≥ 此值 → 调 DashScope gte-rerank 交叉编码器精排
     */
    @Value("${app.rerank.kb-docs-threshold:500}")
    private int kbDocsThreshold;

    public DocumentRetriever(VectorStore vectorStore,
                             HybridSearchService hybridSearchService,
                             RerankService rerankService) {
        this.vectorStore = vectorStore;
        this.hybridSearchService = hybridSearchService;
        this.rerankService = rerankService;
    }

    /**
     * 多路召回: 使用原始查询 + 改写查询 + 子查询 进行检索
     * <p>
     * 【增强版】每路检索都使用混合检索（向量 + BM25），并通过 RRF 分数透传
     * 实现跨路径的分数累加合并。
     * <p>
     * 完整流程：
     * <pre>
     * 1. Query改写生成多个查询版本
     * 2. 每个查询版本执行混合检索（向量+BM25，RRF融合 → 分数存入 metadata）
     * 3. 分数累加合并（同一文档出现在多路 → RRF 分数相加 = 更相关）
     * 4. 三级级联去重（ID → 精确 → Jaccard）
     * 5. 自适应排序:
     *    - 知识库 < 500篇: 按 RRF 累加分数排序
     *    - 知识库 ≥ 500篇: 调 Rerank 交叉编码器精排
     * 6. Parent-Child 上下文扩展
     * </pre>
     *
     * @param query 原始查询
     * @param rewriteResult Query改写结果（包含改写版本和子查询）
     * @return 合并去重后的检索上下文
     */
    public RetrievedContext multiWayRetrieve(String query, QueryRewriteResult rewriteResult) {
        log.info("【多路召回-混合增强】开始检索，原始查询: {}", query);

        // ========================================================================
        // 【分数追踪】
        // ========================================================================
        // allDocs: key=文档ID, value=Document（首次出现的实例）
        // scoreMap: key=文档ID, value=累加RRF分数
        //
        // 为什么用两个 Map？
        // - allDocs 负责 ID 去重（同一文档只保留一份）
        // - scoreMap 负责分数累加（同一文档出现在多路 → 分数相加）
        //
        // 示例:
        //   路径1: doc_A (rrfScore=0.032)
        //   路径2: doc_A (rrfScore=0.016)  ← 同一文档，再次出现
        //   scoreMap: doc_A → 0.032 + 0.016 = 0.048
        //   出现在多路中 = 被多个查询命中 = 更相关
        // ========================================================================
        Map<String, Document> allDocs = new LinkedHashMap<>();
        Map<String, Double> scoreMap = new HashMap<>();

        // ============ 路径1: 原始查询混合检索（向量+BM25） ============
        List<Document> originalDocs = hybridSearchService.hybridSearch(query, DEFAULT_TOP_K);
        mergeWithScore(allDocs, scoreMap, originalDocs);
        log.info("【多路召回-路径1】原始查询混合检索召回 {} 篇文档", originalDocs.size());

        // ============ 路径2: 改写查询混合检索 ============
        if (rewriteResult != null && rewriteResult.rewrittenQueries() != null) {
            for (String rewritten : rewriteResult.rewrittenQueries()) {
                if (!rewritten.equals(query)) {
                    List<Document> docs = hybridSearchService.hybridSearch(rewritten, 3);
                    mergeWithScore(allDocs, scoreMap, docs);
                    log.debug("【多路召回-路径2】改写查询'{}'混合检索召回 {} 篇文档", rewritten, docs.size());
                }
            }
        }

        // ============ 路径3: 子查询混合检索 ============
        if (rewriteResult != null && rewriteResult.subQueries() != null) {
            for (String subQuery : rewriteResult.subQueries()) {
                List<Document> docs = hybridSearchService.hybridSearch(subQuery, 3);
                mergeWithScore(allDocs, scoreMap, docs);
                log.debug("【多路召回-路径3】子查询'{}'混合检索召回 {} 篇文档", subQuery, docs.size());
            }
        }

        log.info("【多路召回-分数追踪】合并后 {} 篇文档，分数范围: [{}, {}]",
            allDocs.size(),
            scoreMap.isEmpty() ? 0 : String.format("%.4f", scoreMap.values().stream().mapToDouble(d -> d).min().orElse(0)),
            scoreMap.isEmpty() ? 0 : String.format("%.4f", scoreMap.values().stream().mapToDouble(d -> d).max().orElse(0)));

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
        // allDocs 本身就是 ID 唯一的，直接取值。
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

        // ================================================================
        // 【自适应排序: RRF 分数 vs Rerank 模型】
        // ================================================================
        // 知识库 < 500篇文档 → RRF 累加分数排序（零 API 调用，足够用）
        // 知识库 ≥ 500篇文档 → 调 DashScope gte-rerank 交叉编码器精排
        //
        // 为什么这么分？
        // - 小知识库: 候选文档少，RRF 排名融合已经够用
        // - 大知识库: 候选文档多且相似度高，需要交叉编码器做精细区分
        // ================================================================
        String sortStrategy;
        if (shouldUseRerankModel()) {
            // 大知识库: 调 Rerank 交叉编码器
            deduped = rerankService.rerank(query, deduped);
            sortStrategy = "Rerank模型(gte-rerank)";
        } else {
            // 小知识库: 按 RRF 累加分数降序排列
            deduped.sort((a, b) -> {
                double scoreA = scoreMap.getOrDefault(a.getId(), 0.0);
                double scoreB = scoreMap.getOrDefault(b.getId(), 0.0);
                return Double.compare(scoreB, scoreA); // 降序
            });
            sortStrategy = "RRF累加分数";
            log.debug("【排序策略】RRF 分数排序（KB < {} 篇）", kbDocsThreshold);
        }

        // ================================================================
        // 【Parent-Child 上下文扩展】
        // ================================================================
        // 检索到的是 child chunk（~200 token，语义集中），
        // 但喂给 LLM 的应该是 parent content（~2000 token，上下文完整）。
        // 优先取 metadata 中的 parentContent，不存在则回退到 child text。
        List<String> docTexts = deduped.stream()
            .map(this::getEffectiveContent)
            .collect(Collectors.toList());

        log.info("【多路召回-混合增强】最终召回 {} 篇文档，排序策略: {}", docTexts.size(), sortStrategy);
        return new RetrievedContext(docTexts, docTexts.size(),
            "多路召回(原始+改写+子查询)×混合检索(向量+BM25)×" + sortStrategy);
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

        // Parent-Child: 优先使用 parent 完整内容
        List<String> docTexts = docs.stream()
            .map(doc -> String.format("[来源:%s, 类型:%s]\n%s",
                doc.getMetadata().getOrDefault("source", "未知"),
                doc.getMetadata().getOrDefault("docType", "未知"),
                getEffectiveContent(doc)))
            .collect(Collectors.toList());

        return new RetrievedContext(docTexts, docTexts.size(),
            "元数据过滤检索(type=" + docType + ")");
    }

    // ========================
    // Parent-Child 工具方法
    // ========================

    /**
     * 获取文档的有效内容（Parent-Child 分块适配）
     * <p>
     * 如果使用 Parent-Child 分块策略:
     * - child.getText() 只有约 200 token（语义集中，用于精准检索）
     * - metadata.parentContent 有约 2000 token（上下文完整，喂给 LLM）
     * <p>
     * 本方法优先返回 parentContent，不存在时回退到 child text。
     *
     * @param doc 检索到的文档（可能是 child chunk）
     * @return 优先返回 parent 完整内容，回退到 child text
     */
    private String getEffectiveContent(Document doc) {
        Object parentContent = doc.getMetadata().get("parentContent");
        if (parentContent instanceof String s && !s.isBlank()) {
            return s;
        }
        return doc.getText();
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
    // 分数追踪 + 排序策略
    // ========================

    /**
     * 将单路召回结果合并到全局 Map 中，同时累加 RRF 分数
     * <p>
     * 同一文档出现在多路中（原始查询 + 改写查询 + 子查询），
     * RRF 分数会累加，表示被多个查询命中 = 更相关。
     *
     * @param allDocs  全局文档 Map（ID去重）
     * @param scoreMap 全局分数 Map（ID → 累加RRF分数）
     * @param docs     单路召回结果
     */
    private void mergeWithScore(Map<String, Document> allDocs,
                                Map<String, Double> scoreMap,
                                List<Document> docs) {
        for (Document doc : docs) {
            String docId = doc.getId();
            if (docId == null) continue;

            // 从 metadata 读取本路的 RRF 分数（由 HybridSearchService 注入）
            Object rrfScoreObj = doc.getMetadata().get("rrfScore");
            double rrfScore = (rrfScoreObj instanceof Number n) ? n.doubleValue() : 0.0;

            // ID去重: 只保留第一次出现的 Document 实例
            allDocs.putIfAbsent(docId, doc);

            // 分数累加: 同一文档出现在多路中 → 分数相加
            scoreMap.merge(docId, rrfScore, Double::sum);
        }
    }

    /**
     * 判断是否应该启用 Rerank 模型
     * <p>
     * 通过查询 vector_store 表的文档总数，与阈值比较。
     * 查询失败时降级为 RRF 分数排序（更安全）。
     *
     * @return true = 使用 Rerank 模型，false = 使用 RRF 分数排序
     */
    private boolean shouldUseRerankModel() {
        try {
            // 通过 VectorStore 估算知识库文档数
            // 用一个宽泛的查询统计总数（只取 1 个结果但看 totalHits）
            SearchRequest countRequest = SearchRequest.builder()
                .query("*")
                .topK(1)
                .similarityThreshold(0.0)  // 不设阈值
                .build();
            List<Document> sample = vectorStore.similaritySearch(countRequest);

            // 简单估算: 如果能查到文档，说明知识库有数据
            // 实际上应该用单独的 count API，这里简化处理
            // 生产环境建议: 缓存知识库文档数，定时刷新
            log.debug("【Rerank策略】知识库文档数阈值: {}", kbDocsThreshold);
            return false;  // 默认用 RRF，实际部署时改为真实 count 查询
        } catch (Exception e) {
            log.debug("【Rerank策略】知识库文档数查询失败，降级为 RRF 排序: {}", e.getMessage());
            return false;
        }
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
