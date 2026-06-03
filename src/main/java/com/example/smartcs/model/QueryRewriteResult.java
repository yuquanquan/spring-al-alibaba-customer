package com.example.smartcs.model;

import java.util.List;

/**
 * Query改写结果
 * <p>
 * Query改写是RAG链路中提升召回率的关键技术，通过多维度改写原始查询，
 * 使得向量检索能够匹配到更多相关文档。
 *
 * @param originalQuery 原始查询
 * @param rewrittenQueries 改写后的查询列表（语义等价但表述不同）
 * @param subQueries 分解后的子查询（复杂问题拆分为多个简单问题）
 */
public record QueryRewriteResult(
    String originalQuery,
    List<String> rewrittenQueries,
    List<String> subQueries
) {}
