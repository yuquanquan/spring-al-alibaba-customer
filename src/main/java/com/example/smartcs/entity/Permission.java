package com.example.smartcs.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.time.LocalDateTime;

/**
 * 权限实体（支持树形结构）
 * 对应业务表 sys_permission
 */
@Entity
@Table(name = "sys_permission")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Permission {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 权限名称 */
    @Column(nullable = false, length = 50)
    private String name;

    /** 权限编码: 如 sys:user:list */
    @Column(nullable = false, unique = true, length = 50)
    private String code;

    @Column(length = 200)
    private String description;

    /** 权限类型: MENU=菜单 / BUTTON=按钮 / API=接口 */
    @Column(length = 20)
    private String type = "MENU";

    /** 父级ID: 0表示顶级 */
    @Column(name = "parent_id")
    private Long parentId = 0L;

    @Column(name = "create_time")
    private LocalDateTime createTime;

    @Column(name = "update_time")
    private LocalDateTime updateTime;
}
