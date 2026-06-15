package com.example.smartcs.repository;

import com.example.smartcs.entity.KnowledgeBaseIndex;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * 知识库索引追踪仓库
 */
@Repository
public interface KnowledgeBaseIndexRepository extends JpaRepository<KnowledgeBaseIndex, Long> {

    /** 按源文件路径查找索引记录 */
    Optional<KnowledgeBaseIndex> findBySourceFile(String sourceFile);

    /** 删除指定源文件的索引记录 */
    void deleteBySourceFile(String sourceFile);
}
