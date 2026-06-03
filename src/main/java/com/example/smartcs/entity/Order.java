package com.example.smartcs.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 订单实体
 * 对应业务表 sys_order
 */
@Entity
@Table(name = "sys_order")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Order {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 订单编号: 如 ORD20260101001 */
    @Column(name = "order_no", nullable = false, unique = true, length = 32)
    private String orderNo;

    /** 关联用户ID */
    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** 订单总金额 */
    @Column(name = "total_amount", nullable = false, precision = 10, scale = 2)
    private BigDecimal totalAmount;

    /**
     * 订单状态:
     * PENDING    - 待支付
     * PAID       - 已支付
     * SHIPPED    - 已发货
     * COMPLETED  - 已完成
     * REFUNDING  - 退款中
     * REFUNDED   - 已退款
     * CANCELLED  - 已取消
     */
    @Column(nullable = false, length = 20)
    private String status = "PENDING";

    @Column(name = "create_time")
    private LocalDateTime createTime;

    @Column(name = "update_time")
    private LocalDateTime updateTime;

    @Column(length = 500)
    private String description;

    /** 订单明细（一对多关联） */
    @OneToMany(cascade = CascadeType.ALL, fetch = FetchType.EAGER)
    @JoinColumn(name = "order_id")
    private List<OrderItem> items = new ArrayList<>();
}
