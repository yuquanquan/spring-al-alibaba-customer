package com.example.smartcs.repository;

import com.example.smartcs.entity.UserFact;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 用户事实仓库（画像记忆 Layer 2）
 * <p>
 * 支持 UPSERT 语义：同一 session + key 只保留最新值。
 * 按重要性排序，用于上下文超限时裁剪低优先级事实。
 */
@Repository
public interface UserFactRepository extends JpaRepository<UserFact, Long> {

    /** 查询会话的所有事实（按重要性降序、类别排序） */
    @Query("SELECT f FROM UserFact f WHERE f.sessionId = :sessionId ORDER BY f.importance DESC, f.category ASC")
    List<UserFact> findBySessionId(String sessionId);

    /** 按 sessionId + factKey 查找（UPSERT 用） */
    Optional<UserFact> findBySessionIdAndFactKey(String sessionId, String factKey);

    /** 统计会话的事实总数 */
    long countBySessionId(String sessionId);

    /** 删除会话的所有事实（清空记忆用） */
    void deleteBySessionId(String sessionId);
}
