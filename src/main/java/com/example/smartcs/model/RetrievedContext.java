package com.example.smartcs.model;

import java.util.List;

/**
 * 检索结果上下文
 * <p>
 * 封装多路召回后的合并结果，用于传递给LLM生成最终回答。
 *
 * @param documents 召回的文档片段列表（已去重、排序）
 * @param totalFound 总共召回的文档数量
 * @param queryType 查询类型描述（如 "多路召回:向量+关键词"）
 */
public record RetrievedContext(
    List<String> documents,
    int totalFound,
    String queryType
) {}
