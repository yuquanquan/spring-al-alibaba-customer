package com.example.smartcs.search;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 全文检索引擎（基于 PostgreSQL tsvector）
 * <p>
 * ========================================================================
 * 【学习要点: PostgreSQL 全文检索 vs 手写 BM25】
 * ========================================================================
 * 之前用 HashMap 手写倒排索引 → 重启丢失、需手动同步、分词简陋
 * 升级为 PostgreSQL tsvector → 零维护、ACID 一致、内置分词引擎
 * <p>
 * tsvector 核心概念：
 * - tsvector: 预处理后的文档向量（词位 + 位置信息）
 * - tsquery: 预处理后的查询向量
 * - ts_rank: 相关性排序函数（类似 BM25 的 TF-IDF）
 * - GIN 索引: 加速全文检索的倒排索引
 * <p>
 * SQL 示例：
 * SELECT id, ts_rank(ts_content, plainto_tsquery('simple', '退货 流程')) AS rank
 * FROM vector_store
 * WHERE ts_content @@ plainto_tsquery('simple', '退货 流程')
 * ORDER BY rank DESC LIMIT 10;
 * <p>
 * 关于中文分词：
 * - 'simple' 配置：按标点/空格分词（英文好，中文按字分词，够用）
 * - 'chinese' 配置：需装 zhparser 扩展（专业中文分词）
 * - 生产环境建议装 zhparser，本项目用 'simple' 即可
 */
@Slf4j
@Component
public class BM25SearchEngine {

    private final JdbcTemplate jdbcTemplate;

    public BM25SearchEngine(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 全文检索（PostgreSQL tsvector 实现）
     * <p>
     * ts_rank 内部实现了类似 BM25 的 TF-IDF 排序算法：
     * - TF: 词频越高分数越高
     * - IDF: 稀有词权重更高
     * - 文档长度归一化
     *
     * @param query 查询文本
     * @param topK 返回结果数量
     * @return 按相关性排序的搜索结果
     */
    public List<SearchResult> search(String query, int topK) {
        if (query == null || query.trim().isEmpty()) {
            log.warn("【全文检索】查询为空");
            return Collections.emptyList();
        }

        try {
            // PostgreSQL 的 plainto_tsquery 自动将文本转为查询语法
            // 'simple' 配置按空格分词，中文按字分词
            String sql = """
                SELECT id,
                       ts_rank(ts_content, plainto_tsquery('simple', ?)) AS rank
                FROM vector_store
                WHERE ts_content @@ plainto_tsquery('simple', ?)
                ORDER BY rank DESC
                LIMIT ?
                """;

            List<SearchResult> results = jdbcTemplate.query(sql,
                (rs, rowNum) -> new SearchResult(
                    rs.getString("id"),
                    rs.getDouble("rank")
                ),
                query, query, topK
            );

            log.info("【全文检索】查询: '{}', 返回 {} 个结果", query, results.size());
            return results;

        } catch (Exception e) {
            log.error("【全文检索】失败: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * 获取索引统计信息（从 PostgreSQL 元数据表查询）
     */
    public Map<String, Object> getStats() {
        Map<String, Object> stats = new LinkedHashMap<>();
        try {
            Integer docCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM vector_store", Integer.class);
            stats.put("totalDocuments", docCount != null ? docCount : 0);
            stats.put("engine", "PostgreSQL tsvector");
            stats.put("index", "GIN ON vector_store(ts_content)");
        } catch (Exception e) {
            stats.put("error", e.getMessage());
        }
        return stats;
    }

    /**
     * 判断 tsvector 列是否存在
     */
    public boolean isReady() {
        try {
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM vector_store WHERE ts_content IS NOT NULL LIMIT 1",
                Integer.class);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    // ========================
    // 内部类：搜索结果
    // ========================

    @Data
    public static class SearchResult {
        /** 文档ID */
        private final String docId;

        /** ts_rank 相关性分数 */
        private final double score;
    }
}
