package com.example.smartcs.search;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 重排序服务（DashScope gte-rerank 交叉编码器）
 * <p>
 * ========================================================================
 * 【学习要点: 重排序 (Reranking) —— RAG 精度提升的关键一步】
 * ========================================================================
 * <p>
 * 为什么需要重排序？
 * <pre>
 *   第一阶段检索（向量 + BM25 + RRF）是"粗排"：
 *   - 向量检索: 双编码器，query 和 doc 分别编码后算余弦相似度（快但不精准）
 *   - BM25: 关键词频率统计（快但不理解语义）
 *   - RRF: 排名融合（不需要归一化分数，但只看排名不看内容）
 *
 *   第二阶段重排序（交叉编码器）是"精排"：
 *   - 将 query 和 doc 拼接后一起编码（慢但精准）
 *   - 模型能看到 query 和 doc 之间的交互关系
 *   - 例: query="退货政策" + doc="7天无理由退货流程"
 *        → 交叉编码器能理解"退货政策"和"退货流程"是高度相关的
 *        → 双编码器可能只给中等分数（因为"政策"和"流程"不同义）
 * </pre>
 * <p>
 * 本项目采用自适应策略（在 DocumentRetriever 中实现）：
 * <pre>
 *   知识库 < 500 篇文档 → RRF 分数排序（零成本，够用）
 *   知识库 ≥ 500 篇文档 → Rerank 模型精排（一次 API 调用，~200ms）
 * </pre>
 * <p>
 * DashScope Rerank API：
 * <pre>
 *   POST https://dashscope.aliyuncs.com/api/v1/services/rerank
 *   Model: gte-rerank
 *   输入: query + documents[]
 *   输出: 每个 document 的相关性分数
 * </pre>
 */
@Slf4j
@Service
public class RerankService {

    @Value("${spring.ai.dashscope.api-key:}")
    private String apiKey;

    @Value("${app.rerank.model:gte-rerank}")
    private String rerankModel;

    @Value("${app.rerank.top-n:5}")
    private int topN;

    @Value("${app.rerank.timeout-ms:5000}")
    private int timeoutMs;

    private static final String RERANK_ENDPOINT =
        "https://dashscope.aliyuncs.com/api/v1/services/rerank";

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build();

    /**
     * 对候选文档进行重排序
     * <p>
     * 调用 DashScope gte-rerank 模型，对 query 和每个 document 计算交叉编码相关性分数，
     * 按分数降序返回 TopN 文档。
     *
     * @param query     用户的原始查询
     * @param documents 候选文档列表（来自多路召回 + 去重后的结果）
     * @return 按相关性重新排序的文档列表（最多 topN 个）
     */
    public List<Document> rerank(String query, List<Document> documents) {
        if (documents == null || documents.isEmpty()) {
            return List.of();
        }

        // 候选数 <= topN 时不需要 rerank，直接返回
        if (documents.size() <= topN) {
            log.debug("【Rerank】候选数({}) <= topN({})，跳过重排序", documents.size(), topN);
            return documents;
        }

        log.info("【Rerank】开始重排序: query='{}', 候选 {} 篇 → Top {}",
            query, documents.size(), topN);

        try {
            // 构建请求体
            List<String> texts = documents.stream()
                .map(Document::getText)
                .collect(Collectors.toList());

            Map<String, Object> input = Map.of("query", query, "documents", texts);
            Map<String, Object> params = Map.of("top_n", topN, "return_documents", false);
            Map<String, Object> body = Map.of(
                "model", rerankModel,
                "input", input,
                "parameters", params
            );

            String requestBody = MAPPER.writeValueAsString(body);

            // 发送 HTTP 请求
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(RERANK_ENDPOINT))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .timeout(Duration.ofMillis(timeoutMs))
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();

            HttpResponse<String> response = HTTP_CLIENT.send(request,
                HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                log.warn("【Rerank】API 返回 {}: {}，降级为 RRF 排序",
                    response.statusCode(), response.body());
                return fallbackSort(documents);
            }

            // 解析响应
            JsonNode root = MAPPER.readTree(response.body());
            JsonNode results = root.path("output").path("results");

            if (results.isMissingNode() || !results.isArray()) {
                log.warn("【Rerank】响应格式异常，降级为 RRF 排序");
                return fallbackSort(documents);
            }

            // 按 rerank 分数排序
            List<ScoredDoc> scoredDocs = new ArrayList<>();
            for (JsonNode node : results) {
                int index = node.path("index").asInt();
                double score = node.path("relevance_score").asDouble();
                if (index >= 0 && index < documents.size()) {
                    Document doc = documents.get(index);
                    doc.getMetadata().put("rerankScore", score);
                    scoredDocs.add(new ScoredDoc(doc, score));
                }
            }

            // 按 rerank 分数降序
            scoredDocs.sort((a, b) -> Double.compare(b.score, a.score));

            List<Document> reranked = scoredDocs.stream()
                .map(sd -> sd.doc)
                .collect(Collectors.toList());

            log.info("【Rerank】完成，最高分: {}, 最低分: {}",
                scoredDocs.isEmpty() ? 0 : String.format("%.4f", scoredDocs.get(0).score),
                scoredDocs.isEmpty() ? 0 : String.format("%.4f",
                    scoredDocs.get(scoredDocs.size() - 1).score));

            return reranked;

        } catch (Exception e) {
            log.warn("【Rerank】调用失败: {}，降级为 RRF 排序", e.getMessage());
            return fallbackSort(documents);
        }
    }

    /**
     * 降级排序: 按 RRF 分数排序（当 Rerank API 不可用时）
     */
    private List<Document> fallbackSort(List<Document> documents) {
        log.info("【Rerank-降级】使用 RRF 分数排序");
        return documents.stream()
            .sorted((a, b) -> {
                double scoreA = getRrfScore(a);
                double scoreB = getRrfScore(b);
                return Double.compare(scoreB, scoreA);
            })
            .limit(topN)
            .collect(Collectors.toList());
    }

    /**
     * 从 Document metadata 中提取 RRF 分数
     */
    static double getRrfScore(Document doc) {
        Object score = doc.getMetadata().get("rrfScore");
        if (score instanceof Number n) {
            return n.doubleValue();
        }
        return 0.0;
    }

    /**
     * 内部类: 带分数的文档
     */
    private record ScoredDoc(Document doc, double score) {}
}
