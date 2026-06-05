package com.example.smartcs.search;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 混合检索服务（向量 + BM25）
 * <p>
 * ========================================================================
 * 【学习要点: 混合检索 (Hybrid Search)】
 * ========================================================================
 * 单一检索方式的局限性：
 * - 向量检索：擅长语义匹配，但专有名词/关键词召回率低
 * - BM25：擅长精确匹配，但无法理解语义相似性
 * <p>
 * 混合检索 = 向量检索 + BM25，结合两者优势：
 * 1. 并行执行两种检索
 * 2. 使用 RRF (Reciprocal Rank Fusion) 融合排名
 * 3. 返回综合最优的结果
 * <p>
 * ========================================================================
 * 【RRF 算法原理】
 * ========================================================================
 * RRF 是一种无参数（或少参数）的排名融合算法，核心思想：
 * <p>
 * RRF_Score(doc) = Σ [ 1 / (k + rank_i) ]
 * <p>
 * 其中：
 * - k: 平滑常数（默认60），避免分母为0，同时降低排名差异的影响
 * - rank_i: 文档在第 i 路检索中的排名（从1开始）
 * <p>
 * 示例计算：
 * 假设有两路检索（向量 + BM25），TopK=5
 * <p>
 * 文档A:
 * - 向量排名第3 → 贡献分数: 1/(60+3) = 0.0159
 * - BM25排名第1 → 贡献分数: 1/(60+1) = 0.0164
 * - RRF总分: 0.0323
 * <p>
 * 文档B:
 * - 向量排名第1 → 贡献分数: 1/(60+1) = 0.0164
 * - BM25排名第10 → 贡献分数: 1/(60+10) = 0.0143
 * - RRF总分: 0.0307
 * <p>
 * 结果：文档A 综合排名更高（因为在两路中都靠前，更稳定）
 * <p>
 * ========================================================================
 * 【为什么 RRF 比加权平均好？】
 * ========================================================================
 * 传统加权平均的问题：
 * - 需要调优权重（向量权重 vs BM25权重）
 * - 不同检索方式的分数范围不一致（向量0~1，BM25可能0~100）
 * - 需要归一化处理
 * <p>
 * RRF 的优势：
 * - 无需调优权重（自动平衡）
 * - 只依赖排名，不依赖具体分数值
 * - 对异常值鲁棒（某一路检索失败不影响整体）
 * - 易于扩展到多路检索（3路、4路...）
 */
@Slf4j
@Service
public class HybridSearchService {
    
    @Autowired
    private VectorStore vectorStore;
    
    @Autowired
    private BM25SearchEngine bm25Engine;
    
    /** RRF 融合常数（经验值60） */
    private static final int RRF_K = 60;
    
    /** 默认每路检索返回的数量（内部使用，最终合并后取TopK） */
    private static final int INTERNAL_TOP_K = 20;
    
    /**
     * 混合检索：向量 + BM25，使用 RRF 融合
     * <p>
     * 完整流程：
     * 1. 并行执行向量检索和 BM25 检索
     * 2. 分别获取两路结果的排名
     * 3. 使用 RRF 公式计算综合分数
     * 4. 按 RRF 分数排序，返回 TopK
     *
     * @param query 查询文本
     * @param topK 最终返回的结果数量
     * @return 融合后的文档列表（按相关性排序）
     */
    public List<Document> hybridSearch(String query, int topK) {
        log.info("【混合检索】开始检索: query='{}', topK={}", query, topK);
        
        // ===== 步骤1: 并行执行两路检索 =====
        
        // 1.1 向量检索
        List<Document> vectorResults = executeVectorSearch(query, INTERNAL_TOP_K);
        log.debug("【混合检索-向量】召回 {} 个文档", vectorResults.size());
        
        // 1.2 BM25 检索
        List<BM25SearchEngine.SearchResult> bm25Results = 
            bm25Engine.search(query, INTERNAL_TOP_K);
        log.debug("【混合检索-BM25】召回 {} 个文档", bm25Results.size());
        
        // ===== 步骤2: 构建排名映射 =====
        
        // 向量检索排名：docId -> rank
        Map<String, Integer> vectorRankMap = new HashMap<>();
        for (int i = 0; i < vectorResults.size(); i++) {
            String docId = vectorResults.get(i).getId();
            if (docId != null) {
                vectorRankMap.put(docId, i + 1);  // 排名从1开始
            }
        }
        
        // BM25 检索排名：docId -> rank
        Map<String, Integer> bm25RankMap = new HashMap<>();
        for (int i = 0; i < bm25Results.size(); i++) {
            bm25RankMap.put(bm25Results.get(i).getDocId(), i + 1);
        }
        
        // ===== 步骤3: RRF 融合 =====
        
        // 合并所有文档ID
        Set<String> allDocIds = new HashSet<>();
        allDocIds.addAll(vectorRankMap.keySet());
        allDocIds.addAll(bm25RankMap.keySet());
        
        // 计算每个文档的 RRF 分数
        Map<String, Double> rrfScores = new HashMap<>();
        for (String docId : allDocIds) {
            double score = 0.0;
            
            // 向量检索贡献
            if (vectorRankMap.containsKey(docId)) {
                int rank = vectorRankMap.get(docId);
                score += 1.0 / (RRF_K + rank);
            }
            
            // BM25 检索贡献
            if (bm25RankMap.containsKey(docId)) {
                int rank = bm25RankMap.get(docId);
                score += 1.0 / (RRF_K + rank);
            }
            
            rrfScores.put(docId, score);
        }
        
        // ===== 步骤4: 按 RRF 分数排序 =====
        
        List<String> sortedDocIds = rrfScores.entrySet().stream()
            .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
            .limit(topK)
            .map(Map.Entry::getKey)
            .collect(Collectors.toList());
        
        log.debug("【混合检索-RRF】融合后文档数: {}", sortedDocIds.size());
        
        // ===== 步骤5: 根据 ID 获取完整文档 =====
        
        // 构建向量结果的快速查找表
        Map<String, Document> vectorDocMap = vectorResults.stream()
            .filter(doc -> doc.getId() != null)
            .collect(Collectors.toMap(Document::getId, doc -> doc, (a, b) -> a));
        
        // 按排序后的 ID 顺序返回文档
        List<Document> finalResults = sortedDocIds.stream()
            .map(vectorDocMap::get)
            .filter(Objects::nonNull)
            .collect(Collectors.toList());
        
        log.info("【混合检索】完成，返回 {} 个文档", finalResults.size());
        return finalResults;
    }
    
    /**
     * 执行向量检索
     * <p>
     * 封装 PgVectorStore 的 similaritySearch 调用
     */
    private List<Document> executeVectorSearch(String query, int topK) {
        try {
            SearchRequest request = SearchRequest.builder()
                .query(query)
                .topK(topK)
                .similarityThreshold(0.3)  // 相似度阈值
                .build();
            
            return vectorStore.similaritySearch(request);
        } catch (Exception e) {
            log.error("【向量检索】失败: {}", e.getMessage());
            return Collections.emptyList();
        }
    }
    
    /**
     * 带元数据过滤的混合检索
     * <p>
     * 支持在特定文档类型中检索（如只在 FAQ 中搜索）
     *
     * @param query 查询文本
     * @param topK 返回结果数量
     * @param docType 文档类型过滤（null 表示不过滤）
     * @return 融合后的文档列表
     */
    public List<Document> hybridSearchWithFilter(String query, int topK, String docType) {
        log.info("【混合检索-过滤】query='{}', docType={}", query, docType);
        
        // TODO: 实现带过滤的混合检索
        // 当前简化实现：只使用向量检索的过滤功能
        // 生产环境需要为 BM25 也实现元数据过滤
        
        try {
            SearchRequest.Builder builder = SearchRequest.builder()
                .query(query)
                .topK(topK)
                .similarityThreshold(0.3);
            
            if (docType != null && !docType.isEmpty()) {
                // 添加元数据过滤
                org.springframework.ai.vectorstore.filter.FilterExpressionBuilder fb =
                    new org.springframework.ai.vectorstore.filter.FilterExpressionBuilder();
                builder.filterExpression(fb.eq("docType", docType).build());
            }
            
            return vectorStore.similaritySearch(builder.build());
        } catch (Exception e) {
            log.error("【混合检索-过滤】失败: {}", e.getMessage());
            return Collections.emptyList();
        }
    }
    
    /**
     * 同步 BM25 索引与向量存储
     * <p>
     * 当向量存储中有新文档时，需要同步添加到 BM25 索引
     * <p>
     * 注意：这是一个简化的实现，生产环境建议使用事件驱动或消息队列
     *
     * @param documents 需要同步的文档列表
     */
    public void syncBM25Index(List<Document> documents) {
        log.info("【BM25同步】开始同步 {} 个文档到 BM25 索引", documents.size());
        
        Map<String, String> docMap = new HashMap<>();
        for (Document doc : documents) {
            if (doc.getId() != null && doc.getText() != null) {
                docMap.put(doc.getId(), doc.getText());
            }
        }
        
        bm25Engine.bulkAddDocuments(docMap);
        
        log.info("【BM25同步】完成，BM25 索引统计: {}", bm25Engine.getStats());
    }
}
