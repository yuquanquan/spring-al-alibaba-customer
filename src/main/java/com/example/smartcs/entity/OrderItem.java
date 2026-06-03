package com.example.smartcs.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.math.BigDecimal;

/**
 * 订单明细实体
 * 对应业务表 sys_order_item
 */
@Entity
@Table(name = "sys_order_item")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class OrderItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 商品名称 */
    @Column(name = "product_name", nullable = false, length = 100)
    private String productName;

    /** 数量 */
    @Column(nullable = false)
    private Integer quantity;

    /** 单价 */
    @Column(name = "unit_price", nullable = false, precision = 10, scale = 2)
    private BigDecimal unitPrice;

    /** 小计金额 */
    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal subtotal;
}
