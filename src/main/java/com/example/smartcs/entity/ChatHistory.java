package com.example.smartcs.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.time.LocalDateTime;

/**
 * 对话历史实体（长期记忆）
 * <p>
 * 存储完整的对话记录，支持跨会话恢复上下文
 */
@Entity
@Table(name = "chat_history")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ChatHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 会话ID（用户唯一标识） */
    @Column(name = "session_id", nullable = false, length = 64)
    private String sessionId;

    /** 消息角色: USER / ASSISTANT / SYSTEM */
    @Column(nullable = false, length = 20)
    private String role;

    /** 消息内容 */
    @Column(columnDefinition = "TEXT")
    private String content;

    /** 消息序号（用于排序） */
    @Column(name = "message_index")
    private Integer messageIndex;

    /** Token 数量（用于统计和压缩决策） */
    @Column(name = "token_count")
    private Integer tokenCount;

    /** 创建时间 */
    @Column(name = "create_time")
    private LocalDateTime createTime;

    /** 是否已压缩（总结后标记） */
    @Column
    private Boolean compressed = false;
}
