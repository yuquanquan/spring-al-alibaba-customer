-- ==============================================================================
-- 智能客服系统 - 数据库初始化脚本
-- ==============================================================================
-- 业务表: 用户(sys_user)、订单(sys_order / sys_order_item)、权限(sys_permission)
-- 向量表: vector_store 由 Spring AI PgVectorStore 自动创建
-- ==============================================================================

-- 启用pgvector扩展（向量搜索所需，Spring AI也会自动执行此命令）
CREATE EXTENSION IF NOT EXISTS vector;

-- ========================
-- 用户表
-- ========================
CREATE TABLE IF NOT EXISTS sys_user (
    id          BIGSERIAL PRIMARY KEY,
    username    VARCHAR(50) NOT NULL UNIQUE,
    password    VARCHAR(100) NOT NULL,
    email       VARCHAR(100),
    phone       VARCHAR(20),
    role        VARCHAR(20) DEFAULT 'USER',
    status      INTEGER DEFAULT 1,
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- ========================
-- 订单表
-- ========================
CREATE TABLE IF NOT EXISTS sys_order (
    id           BIGSERIAL PRIMARY KEY,
    order_no     VARCHAR(32) NOT NULL UNIQUE,
    user_id      BIGINT NOT NULL,
    total_amount NUMERIC(10,2) NOT NULL,
    status       VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    create_time  TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time  TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    description  VARCHAR(500)
);

-- ========================
-- 订单明细表
-- ========================
CREATE TABLE IF NOT EXISTS sys_order_item (
    id           BIGSERIAL PRIMARY KEY,
    order_id     BIGINT NOT NULL,
    product_name VARCHAR(100) NOT NULL,
    quantity     INTEGER NOT NULL,
    unit_price   NUMERIC(10,2) NOT NULL,
    subtotal     NUMERIC(10,2) NOT NULL
);

-- ========================
-- 权限表（支持树形结构）
-- ========================
CREATE TABLE IF NOT EXISTS sys_permission (
    id          BIGSERIAL PRIMARY KEY,
    name        VARCHAR(50) NOT NULL,
    code        VARCHAR(50) NOT NULL UNIQUE,
    description VARCHAR(200),
    type        VARCHAR(20) DEFAULT 'MENU',
    parent_id   BIGINT DEFAULT 0,
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- ========================
-- 示例数据
-- ========================

-- 用户数据
INSERT INTO sys_user (username, password, email, phone, role, status) VALUES
('zhangsan', 'pass123', 'zhangsan@example.com', '13800138001', 'ADMIN', 1),
('lisi',     'pass456', 'lisi@example.com',     '13800138002', 'USER',  1),
('wangwu',   'pass789', 'wangwu@example.com',   '13800138003', 'USER',  1),
('zhaoliu',  'pass000', 'zhaoliu@example.com',  '13800138004', 'USER',  0)
ON CONFLICT (username) DO NOTHING;

-- 权限数据
INSERT INTO sys_permission (name, code, description, type, parent_id) VALUES
('系统管理',   'sys:manage',     '系统管理模块',      'MENU',   0),
('用户管理',   'sys:user:list',  '查看用户列表',      'MENU',   1),
('用户新增',   'sys:user:add',   '新增用户权限',      'BUTTON', 1),
('订单管理',   'order:manage',   '订单管理模块',      'MENU',   0),
('订单查看',   'order:view',     '查看订单列表',      'MENU',   4),
('订单导出',   'order:export',   '导出订单数据',      'BUTTON', 4),
('知识库管理', 'kb:manage',      '知识库管理模块',    'MENU',   0),
('文档上传',   'kb:upload',      '上传知识库文档',    'BUTTON', 7)
ON CONFLICT (code) DO NOTHING;

-- 订单数据
INSERT INTO sys_order (order_no, user_id, total_amount, status, description) VALUES
('ORD20260101001', 1, 5299.00,  'COMPLETED',  '购买智能客服企业版年度授权'),
('ORD20260115002', 2, 199.00,   'COMPLETED',  '购买云存储100GB套餐'),
('ORD20260201003', 1, 99.00,    'SHIPPED',    '购买自定义域名服务'),
('ORD20260215004', 3, 299.00,   'PENDING',    '购买多语言支持包'),
('ORD20260301005', 2, 12999.00, 'REFUNDING',  '购买私有化部署方案')
ON CONFLICT (order_no) DO NOTHING;

-- 订单明细
INSERT INTO sys_order_item (order_id, product_name, quantity, unit_price, subtotal) VALUES
(1, '智能客服企业版-年付',   1, 4999.00, 4999.00),
(1, '高级模板包',            1,  300.00,  300.00),
(2, '云存储100GB',           1,  199.00,  199.00),
(3, '自定义域名-年付',       1,   99.00,   99.00),
(4, '多语言支持包-年付',     1,  299.00,  299.00),
(5, '私有化部署标准版',      1, 12999.00, 12999.00)
ON CONFLICT DO NOTHING;
