package com.example.smartcs.controller;

import com.example.smartcs.search.BM25SearchEngine;
import com.example.smartcs.search.HybridSearchService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 混合检索测试控制器
 * <p>
 * 用于对比纯向量检索 vs 混合检索（向量+BM25）的效果
 */
@Slf4j
@RestController
@RequestMapping("/api/search")
public class SearchTestController {

    private final HybridSearchService hybridSearchService;
    private final BM25SearchEngine bm25Engine;

    public SearchTestController(HybridSearchService hybridSearchService,
                                BM25SearchEngine bm25Engine) {
        this.hybridSearchService = hybridSearchService;
        this.bm25Engine = bm25Engine;
    }

    /**
     * 测试混合检索
     * <p>
     * 对比场景：
     * 1. 专有名词查询（订单号、用户ID）→ BM25 优势明显
     * 2. 语义查询（"怎么退货"）→ 向量检索优势明显
     * 3. 混合查询 → RRF 自动平衡
     *
     * @param query 查询文本
     * @param topK 返回结果数量
     * @param mode 检索模式: "hybrid"=混合检索, "vector"=纯向量, "bm25"=纯BM25
     */
    @GetMapping("/test")
    public Map<String, Object> testSearch(
            @RequestParam String query,
            @RequestParam(defaultValue = "5") int topK,
            @RequestParam(defaultValue = "hybrid") String mode) {

        log.info("【搜索测试】query='{}', mode={}", query, mode);

        Map<String, Object> result = new HashMap<>();
        result.put("query", query);
        result.put("mode", mode);

        switch (mode.toLowerCase()) {
            case "hybrid":
                // 混合检索（RRF融合）
                List<Document> hybridResults = hybridSearchService.hybridSearch(query, topK);
                result.put("results", formatDocuments(hybridResults));
                result.put("count", hybridResults.size());
                break;

            case "bm25":
                // 纯 BM25 检索
                List<BM25SearchEngine.SearchResult> bm25Results = bm25Engine.search(query, topK);
                result.put("results", bm25Results);
                result.put("count", bm25Results.size());
                break;

            case "vector":
                // TODO: 纯向量检索（需要调用 VectorStore）
                result.put("message", "纯向量检索请使用 /api/docs/search 接口");
                break;

            default:
                result.put("error", "不支持的检索模式: " + mode);
        }

        // 添加 BM25 索引统计
        result.put("bm25Stats", bm25Engine.getStats());

        return result;
    }

    /**
     * 格式化文档列表（简化输出）
     */
    private List<Map<String, Object>> formatDocuments(List<Document> docs) {
        return docs.stream().map(doc -> {
            Map<String, Object> map = new HashMap<>();
            map.put("id", doc.getId());
            map.put("content", doc.getText().substring(0, Math.min(100, doc.getText().length())) + "...");
            map.put("metadata", doc.getMetadata());
            return map;
        }).collect(Collectors.toList());
    }

    /**
     * 手动添加测试文档到 BM25 索引
     * <p>
     * 用于快速测试，无需重新初始化整个知识库
     */
    @PostMapping("/bm25/add")
    public Map<String, Object> addTestDocument(@RequestBody Map<String, String> document) {
        String docId = document.get("id");
        String content = document.get("content");

        if (docId == null || content == null) {
            return Map.of("error", "需要提供 id 和 content 字段");
        }

        bm25Engine.addDocument(docId, content);
        return Map.of(
            "status", "success",
            "docId", docId,
            "stats", bm25Engine.getStats()
        );
    }
}
