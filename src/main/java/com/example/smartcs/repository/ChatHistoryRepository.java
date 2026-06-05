package com.example.smartcs.repository;

import com.example.smartcs.entity.ChatHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ChatHistoryRepository extends JpaRepository<ChatHistory, Long> {
    
    /** 查询会话的完整历史（按序号排序） */
    List<ChatHistory> findBySessionIdOrderByMessageIndexAsc(String sessionId);
    
    /** 查询最近 N 条消息（短期记忆恢复用） */
    List<ChatHistory> findTop10BySessionIdOrderByMessageIndexDesc(String sessionId);
    
    /** 统计会话的消息总数 */
    long countBySessionId(String sessionId);
    
    /** 查询未压缩的历史消息 */
    @Query("SELECT h FROM ChatHistory h WHERE h.sessionId = :sessionId AND h.compressed = false ORDER BY h.messageIndex ASC")
    List<ChatHistory> findUncompressedHistory(String sessionId);
    
    /** 删除已压缩的旧消息（节省空间） */
    void deleteBySessionIdAndCompressedTrue(String sessionId);
}
