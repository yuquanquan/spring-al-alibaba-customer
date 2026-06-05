package com.example.smartcs.search;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * BM25 搜索引擎（手动实现）
 * <p>
 * ========================================================================
 * 【学习要点: BM25 算法】
 * ========================================================================
 * BM25 (Best Matching 25) 是一种基于词频的排序算法，广泛用于全文检索。
 * <p>
 * 核心公式：
 * Score(Q,D) = Σ [ IDF(qi) * (f(qi,D) * (k1 + 1)) / (f(qi,D) + k1 * (1 - b + b * |D|/avgdl)) ]
 * <p>
 * 参数说明：
 * - f(qi,D): 词 qi 在文档 D 中的词频 (Term Frequency)
 * - IDF(qi): 逆文档频率 = log((N - n + 0.5) / (n + 0.5))
 *   - N: 总文档数
 *   - n: 包含词 qi 的文档数
 * - |D|: 文档长度（词数）
 * - avgdl: 平均文档长度
 * - k1: 词频饱和参数（默认 1.2），控制 TF 增长的上限
 * - b: 文档长度归一化参数（默认 0.75），控制长文档的惩罚程度
 * <p>
 * ========================================================================
 * 【为什么需要 BM25？】
 * ========================================================================
 * 向量检索擅长语义匹配，但在以下场景表现不佳：
 * 1. 专有名词精确匹配：如订单号 "ORD20260101"、用户ID
 * 2. 关键词召回：用户明确搜索特定术语时
 * 3. 短文本检索：FAQ 问答对等短文本，向量相似度区分度低
 * <p>
 * BM25 优势：
 * - 精确匹配能力强（基于词频）
 * - 计算速度快（倒排索引）
 * - 可解释性好（知道哪些词匹配了）
 * <p>
 * ========================================================================
 * 【与向量检索的融合策略】
 * ========================================================================
 * 混合检索（Hybrid Search）= 向量检索 + BM25，使用 RRF 算法融合：
 * <p>
 * RRF (Reciprocal Rank Fusion) 公式：
 * RRF_Score(doc) = Σ [ 1 / (k + rank_i) ]
 * - k: 常数（通常 60），平滑排名差异
 * - rank_i: 文档在第 i 路检索中的排名
 * <p>
 * 示例：
 * 文档A: 向量排名第3，BM25排名第1 → RRF = 1/(60+3) + 1/(60+1) = 0.032
 * 文档B: 向量排名第1，BM25排名第10 → RRF = 1/(60+1) + 1/(60+10) = 0.030
 * 结果：文档A 综合排名更高（两路都靠前）
 */
@Slf4j
@Component
public class BM25SearchEngine {
    
    // ========================
    // BM25 超参数（可调优）
    // ========================
    private static final double K1 = 1.2;  // 词频饱和参数
    private static final double B = 0.75;  // 文档长度归一化参数
    private static final int RRF_K = 60;   // RRF 融合常数
    
    // ========================
    // 数据结构
    // ========================
    
    /** 倒排索引：词 -> 文档ID集合 */
    private final Map<String, Set<String>> invertedIndex = new ConcurrentHashMap<>();
    
    /** 文档存储：文档ID -> 文档内容 */
    private final Map<String, String> documents = new ConcurrentHashMap<>();
    
    /** 词频统计：文档ID -> (词 -> 出现次数) */
    private final Map<String, Map<String, Integer>> termFreqMap = new ConcurrentHashMap<>();
    
    /** 文档长度缓存：文档ID -> 词数 */
    private final Map<String, Integer> docLengthMap = new ConcurrentHashMap<>();
    
    /** 总文档数 */
    private volatile int totalDocs = 0;
    
    /** 总词数（用于计算平均文档长度） */
    private volatile long totalTokens = 0;
    
    /**
     * 添加文档到索引
     * <p>
     * 线程安全：使用 synchronized 保证并发写入时的数据一致性
     * <p>
     * 生产环境优化建议：
     * 1. 批量导入时使用 bulkAddDocuments() 减少锁竞争
     * 2. 中文分词建议使用 HanLP 或 Jieba，而非简单空格分割
     * 3. 停用词过滤（移除"的"、"是"等无意义词）
     *
     * @param docId 文档唯一ID
     * @param content 文档内容
     */
    public synchronized void addDocument(String docId, String content) {
        if (documents.containsKey(docId)) {
            log.warn("文档已存在，跳过: {}", docId);
            return;
        }
        
        documents.put(docId, content);
        
        // 分词（简单按空格和标点分割）
        List<String> tokens = tokenize(content);
        
        // 统计词频
        Map<String, Integer> termFreq = new HashMap<>();
        for (String token : tokens) {
            termFreq.merge(token, 1, Integer::sum);
            
            // 更新倒排索引
            invertedIndex.computeIfAbsent(token, k -> ConcurrentHashMap.newKeySet())
                .add(docId);
        }
        
        termFreqMap.put(docId, termFreq);
        docLengthMap.put(docId, tokens.size());
        
        totalDocs++;
        totalTokens += tokens.size();
        
        log.debug("【BM25索引】添加文档: {}, 词数: {}", docId, tokens.size());
    }
    
    /**
     * 批量添加文档（性能优化版本）
     * <p>
     * 适用于知识库初始化时的大规模文档导入
     *
     * @param docMap 文档映射 (docId -> content)
     */
    public synchronized void bulkAddDocuments(Map<String, String> docMap) {
        log.info("【BM25索引】开始批量导入 {} 个文档", docMap.size());
        
        for (Map.Entry<String, String> entry : docMap.entrySet()) {
            addDocument(entry.getKey(), entry.getValue());
        }
        
        log.info("【BM25索引】批量导入完成，总文档数: {}", totalDocs);
    }
    
    /**
     * BM25 搜索
     * <p>
     * 计算每个文档与查询的相关性分数，返回 TopK 结果
     *
     * @param query 查询文本
     * @param topK 返回结果数量
     * @return 按相关性排序的搜索结果列表
     */
    public List<SearchResult> search(String query, int topK) {
        if (totalDocs == 0) {
            log.warn("【BM25搜索】索引为空，无法搜索");
            return Collections.emptyList();
        }
        
        List<String> queryTokens = tokenize(query);
        
        if (queryTokens.isEmpty()) {
            log.warn("【BM25搜索】查询分词结果为空: {}", query);
            return Collections.emptyList();
        }
        
        // 计算每个文档的 BM25 分数
        Map<String, Double> scores = new HashMap<>();
        
        for (String token : queryTokens) {
            Set<String> docIds = invertedIndex.getOrDefault(token, Collections.emptySet());
            
            if (docIds.isEmpty()) {
                continue;  // 该词不在任何文档中出现，跳过
            }
            
            // 计算 IDF（逆文档频率）
            double idf = calculateIDF(docIds.size());
            
            for (String docId : docIds) {
                // 计算 TF 部分
                int termFreq = termFreqMap.get(docId).getOrDefault(token, 0);
                int docLength = docLengthMap.get(docId);
                
                // BM25 公式的核心部分
                double tfScore = (termFreq * (K1 + 1)) / 
                    (termFreq + K1 * (1 - B + B * ((double) docLength / getAvgDocLength())));
                
                // 累加分数（多个查询词的分数相加）
                scores.merge(docId, idf * tfScore, Double::sum);
            }
        }
        
        // 按分数降序排序，返回 TopK
        List<SearchResult> results = scores.entrySet().stream()
            .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
            .limit(topK)
            .map(entry -> new SearchResult(entry.getKey(), entry.getValue()))
            .collect(Collectors.toList());
        
        log.debug("【BM25搜索】查询: '{}', 返回 {} 个结果", query, results.size());
        return results;
    }
    
    /**
     * 计算 IDF（逆文档频率）
     * <p>
     * IDF 衡量一个词的稀有程度：
     * - 出现在很多文档中的词（如"的"）→ IDF 低
     * - 只出现在少数文档中的词（如"退款"）→ IDF 高
     * <p>
     * 公式：IDF = log((N - n + 0.5) / (n + 0.5) + 1.0)
     * - N: 总文档数
     * - n: 包含该词的文档数
     *
     * @param docCount 包含该词的文档数
     * @return IDF 值
     */
    private double calculateIDF(int docCount) {
        return Math.log((totalDocs - docCount + 0.5) / (docCount + 0.5) + 1.0);
    }
    
    /**
     * 计算平均文档长度
     */
    private double getAvgDocLength() {
        return totalDocs > 0 ? (double) totalTokens / totalDocs : 1.0;
    }
    
    /**
     * 简单分词（生产环境建议用 HanLP 或 Jieba）
     * <p>
     * 当前实现：
     * 1. 转小写
     * 2. 移除非字母数字和非中文字符
     * 3. 按空格分割
     * <p>
     * TODO: 集成中文分词器
     * - HanLP: https://github.com/hankcs/HanLP
     * - Jieba: https://github.com/huaban/jieba-analysis
     */
    private List<String> tokenize(String text) {
        return Arrays.stream(text.toLowerCase()
                .replaceAll("[^a-z0-9\\u4e00-\\u9fa5\\s]", " ")  // 保留中文和英文数字
                .split("\\s+"))
            .filter(token -> !token.isEmpty() && token.length() > 1)  // 过滤单字符（可能是噪声）
            .collect(Collectors.toList());
    }
    
    /**
     * 删除文档（支持动态更新索引）
     *
     * @param docId 文档ID
     */
    public synchronized void removeDocument(String docId) {
        if (!documents.containsKey(docId)) {
            return;
        }
        
        String content = documents.remove(docId);
        Map<String, Integer> termFreq = termFreqMap.remove(docId);
        Integer docLength = docLengthMap.remove(docId);
        
        // 从倒排索引中移除
        if (termFreq != null) {
            for (String token : termFreq.keySet()) {
                Set<String> docIds = invertedIndex.get(token);
                if (docIds != null) {
                    docIds.remove(docId);
                    if (docIds.isEmpty()) {
                        invertedIndex.remove(token);  // 清理空集合
                    }
                }
            }
        }
        
        totalDocs--;
        if (docLength != null) {
            totalTokens -= docLength;
        }
        
        log.debug("【BM25索引】删除文档: {}", docId);
    }
    
    /**
     * 获取索引统计信息
     */
    public Map<String, Object> getStats() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("totalDocuments", totalDocs);
        stats.put("totalTokens", totalTokens);
        stats.put("avgDocLength", getAvgDocLength());
        stats.put("uniqueTerms", invertedIndex.size());
        return stats;
    }
    
    // ========================
    // 内部类：搜索结果
    // ========================
    
    @Data
    public static class SearchResult {
        /** 文档ID */
        private final String docId;
        
        /** BM25 相关性分数 */
        private final double score;
    }
}
