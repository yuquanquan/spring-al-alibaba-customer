package com.example.smartcs.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.time.LocalDateTime;

/**
 * 用户事实实体（画像记忆 - Layer 2）
 * <p>
 * ========================================================================
 * 【企业级设计: 结构化事实存储，永不压缩】
 * ========================================================================
 * <p>
 * 解决滚动摘要的"信息衰减"问题：
 * <pre>
 * 滚动摘要: "用户叫张三" → "用户咨询过" → "用户是活跃客户" → 信息丢失💀
 * 画像记忆: fact_key=user_name, fact_value=张三 → 永远不变 ✅
 * </pre>
 * <p>
 * 每条事实是一个 key-value 对，支持：
 * - UPSERT: 同一 key 更新而非追加（如用户名变更）
 * - 分类: PROFILE(身份) / PREFERENCE(偏好) / DECISION(决策) / ENTITY(关键实体)
 * - 重要性: 1-5 级，上下文超限时按重要性裁剪
 * <p>
 * 典型事实示例：
 * <pre>
 *   user_name        = 张三          (PROFILE, importance=5)
 *   user_email       = z@x.com       (PROFILE, importance=4)
 *   preferred_lang   = 中文           (PREFERENCE, importance=3)
 *   last_order_id    = ORD001        (ENTITY, importance=3)
 *   refund_policy_ok = 已确认7天无理由 (DECISION, importance=4)
 * </pre>
 */
@Entity
@Table(name = "user_fact",
    uniqueConstraints = @UniqueConstraint(columnNames = {"session_id", "fact_key"}))
@Data
@NoArgsConstructor
@AllArgsConstructor
public class UserFact {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 会话ID */
    @Column(name = "session_id", nullable = false, length = 64)
    private String sessionId;

    /** 事实键（唯一标识，如 user_name） */
    @Column(name = "fact_key", nullable = false, length = 64)
    private String factKey;

    /** 事实值（如 "张三"） */
    @Column(name = "fact_value", nullable = false, length = 500)
    private String factValue;

    /**
     * 事实分类:
     * - PROFILE: 用户身份信息（姓名、邮箱、角色等）
     * - PREFERENCE: 用户偏好（语言、风格、习惯等）
     * - DECISION: 重要决策和结论（已确认的方案、已同意条款等）
     * - ENTITY: 关键业务实体（订单号、产品名、项目编号等）
     */
    @Column(length = 20)
    private String category;

    /** 重要性 1-5（5=最重要，上下文超限时按此排序裁剪） */
    @Column
    private Integer importance = 3;

    /** 创建时间 */
    @Column(name = "create_time")
    private LocalDateTime createTime;

    /** 更新时间（每次 UPSERT 刷新） */
    @Column(name = "update_time")
    private LocalDateTime updateTime;
}
