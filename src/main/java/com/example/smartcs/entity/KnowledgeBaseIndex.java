package com.example.smartcs.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 知识库索引追踪表 —— 企业级增量同步的核心
 * <p>
 * ========================================================================
 * 【学习要点: Content-Addressable 增量同步】
 * ========================================================================
 * 企业场景：知识库文档持续更新（新增/修改/删除），不能每次全量重新建索引。
 * 
 * 本条记录的核心字段：
 * - sourceFile: 源文件路径（稳定标识，不随向量化改变）
 * - contentHash: 文档去噪后内容的 MD5（用于快速判断是否变化）
 * - chunkIds: 该文档产生的所有 chunk ID 列表（用于定向删除旧向量）
 * - chunkCount: chunk 数量（统计用）
 * - version: 递增版本号（每次更新+1，便于后续做版本回滚）
 * 
 * 增量同步流程：
 * 1. 读取 DB 中所有已索引的 sourceFile → 得到"旧快照"
 * 2. 读取磁盘上当前文件列表 → 得到"新快照"
 * 3. 对比：
 *    - 新有旧无 → ADD（新增文档）
 *    - 新无旧有 → DELETE（删除文档，清理旧向量）
 *    - 新旧 hash 不同 → UPDATE（先删旧向量，再插入新向量）
 *    - 新旧 hash 相同 → SKIP（跳过，省 embedding 成本）
 */
@Entity
@Table(name = "knowledge_base_index")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KnowledgeBaseIndex {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 源文件路径（唯一标识，如 "FAQ.md"、"policies/return.md"） */
    @Column(nullable = false, unique = true, length = 500)
    private String sourceFile;

    /** 文档去噪后纯文本的 MD5 哈希 */
    @Column(nullable = false, length = 64)
    private String contentHash;

    /** 该文档产生的所有 chunk ID（JSON 数组格式，如 ["uuid1","uuid2"]） */
    @Column(columnDefinition = "TEXT")
    private String chunkIds;

    /** chunk 数量 */
    @Column(nullable = false)
    private Integer chunkCount;

    /** 文件最后修改时间 */
    private LocalDateTime fileModifiedAt;

    /** 索引版本号（每次更新递增） */
    @Column(nullable = false)
    @Builder.Default
    private Integer version = 1;

    @Column(nullable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(nullable = false)
    @Builder.Default
    private LocalDateTime updatedAt = LocalDateTime.now();

    @PreUpdate
    public void preUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
