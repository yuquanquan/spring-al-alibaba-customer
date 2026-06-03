package com.example.smartcs.config;

/**
 * 提示词模板管理
 * <p>
 * 集中管理所有 Prompt 模板。提示词工程是 AI 应用的核心技能，
 * 一个好的 Prompt 可以显著提升 AI 输出的质量和准确性。
 * <p>
 * 【提示词工程核心原则】:
 * 1. 明确任务: 清晰告诉 LLM 要做什么
 * 2. 提供上下文: 给出必要的背景信息
 * 3. 指定格式: 要求输出特定格式（JSON/Markdown等）
 * 4. Few-Shot示例: 提供输入输出示例帮助 LLM 理解
 * 5. 约束条件: 设置边界和限制（不要编造、不要超出范围等）
 */
public class PromptTemplates {

    // ========================
    // 1. 意图识别 Prompt
    // ========================
    /**
     * 意图识别提示词模板
     * <p>
     * 关键技术点：
     * - 明确列出所有可能的意图类型及其判断标准
     * - 给出每个意图的典型示例（Few-Shot）
     * - 要求输出置信度和推理过程（思维链 CoT）
     * - 使用 BeanOutputParser 约束输出格式
     */
    public static final String INTENT_RECOGNITION = """
        你是一个意图识别专家。请分析用户输入的意图，判断属于以下哪种类型：
        
        1. CHAT（闲聊）: 日常问候、打招呼、闲聊、感谢、告别等
           - 示例: "你好"、"谢谢"、"今天天气怎么样"、"你真聪明"
        
        2. RAG（知识库查询）: 关于产品功能、使用方法、价格、政策、退货规则等知识库中可能包含的信息
           - 示例: "怎么退货"、"产品有哪些功能"、"价格是多少"、"支持哪些文件格式"
        
        3. DB_QUERY（数据库查询）: 需要查询具体业务数据的问题，如用户信息、订单详情、权限配置等
           - 示例: "张三的订单有哪些"、"查看所有管理员"、"订单ORD001的状态"、"有多少待处理的订单"
        
        判断规则：
        - 如果用户问的是通用知识或产品信息，优先判断为 RAG
        - 如果用户提到了具体的人名、订单号、或需要查看/统计数据，判断为 DB_QUERY
        - 如果只是打招呼或闲聊，判断为 CHAT
        
        用户输入: {userInput}
        
        {format}
        """;

    // ========================
    // 2. Query改写 Prompt
    // ========================
    /**
     * Query改写提示词模板
     * <p>
     * Query改写的目的是提高向量检索的召回率。用户原始查询可能存在以下问题：
     * - 表述模糊: "怎么退" → "退货流程是什么"
     * - 缺少关键词: "那个功能" → 需要扩展上下文
     * - 复合问题: "功能和价格" → 需要拆分为子查询
     * <p>
     * 改写策略：
     * 1. 语义保持改写: 换一种表述方式，保持语义不变
     * 2. 关键词扩展: 添加同义词、相关词
     * 3. 查询分解: 复合问题拆分为多个子问题
     */
    public static final String QUERY_REWRITE = """
        你是一个搜索查询优化专家。请对用户查询进行多维度改写以提高搜索召回率：
        
        改写策略：
        1. 语义保持改写: 用不同的表述方式表达相同的意思
        2. 关键词扩展: 添加同义词或相关术语
        3. 查询分解: 如果是复合问题，拆分为多个子问题
        
        用户查询: {userQuery}
        
        请以JSON格式返回，包含以下字段：
        - "rewritten": ["改写版本1", "改写版本2"]（2-3个改写版本）
        - "subQueries": ["子查询1", "子查询2"]（如果是复合问题则分解，否则为空数组）
        
        只返回JSON，不要其他内容。
        """;

    // ========================
    // 3. RAG回答 Prompt
    // ========================
    /**
     * RAG 生成回答的提示词模板
     * <p>
     * 这是 RAG 链路中最后一步：将检索到的上下文注入 Prompt，
     * 让 LLM 基于真实数据生成回答，减少幻觉。
     * <p>
     * 关键点：
     * - 明确区分"上下文"和"用户问题"
     * - 约束 LLM 只能基于上下文回答
     * - 当上下文不足时，要求 LLM 坦诚告知
     */
    public static final String RAG_ANSWER = """
        请基于以下参考信息回答用户的问题。
        
        【参考信息】:
        {context}
        
        【用户问题】:
        {question}
        
        回答要求：
        1. 优先使用参考信息中的内容来回答
        2. 如果参考信息中没有相关内容，请诚实告知用户"根据现有知识库暂时无法回答该问题"
        3. 回答要结构化、清晰，适当使用列表和分点说明
        4. 不要编造参考信息中没有的内容
        """;

    // ========================
    // 4. NL2SQL Prompt
    // ========================
    /**
     * 自然语言转SQL的提示词模板
     * <p>
     * 将用户的自然语言查询转换为安全的 SQL 语句。
     * <p>
     * 安全考虑：
     * - 只允许 SELECT 语句
     * - 限定可查询的表
     * - 防止 SQL 注入
     */
    public static final String NL2SQL = """
        你是一个SQL专家。根据用户的自然语言问题，生成安全的SQL查询语句。
        
        可用的数据库表结构：
        
        1. sys_user（用户表）:
           - id (BIGINT): 用户ID
           - username (VARCHAR): 用户名
           - email (VARCHAR): 邮箱
           - phone (VARCHAR): 手机号
           - role (VARCHAR): 角色 (ADMIN=管理员 / USER=普通用户)
           - status (INTEGER): 状态 (1=正常 / 0=禁用)
           - create_time (TIMESTAMP): 创建时间
        
        2. sys_order（订单表）:
           - id (BIGINT): 订单ID
           - order_no (VARCHAR): 订单编号
           - user_id (BIGINT): 用户ID
           - total_amount (NUMERIC): 总金额
           - status (VARCHAR): 状态 (PENDING=待支付 / PAID=已支付 / SHIPPED=已发货 / COMPLETED=已完成 / REFUNDING=退款中 / REFUNDED=已退款 / CANCELLED=已取消)
           - description (VARCHAR): 订单描述
           - create_time (TIMESTAMP): 创建时间
        
        3. sys_order_item（订单明细表）:
           - id (BIGINT): 明细ID
           - order_id (BIGINT): 订单ID
           - product_name (VARCHAR): 商品名称
           - quantity (INTEGER): 数量
           - unit_price (NUMERIC): 单价
           - subtotal (NUMERIC): 小计
        
        4. sys_permission（权限表）:
           - id (BIGINT): 权限ID
           - name (VARCHAR): 权限名称
           - code (VARCHAR): 权限编码
           - type (VARCHAR): 类型 (MENU=菜单 / BUTTON=按钮 / API=接口)
           - parent_id (BIGINT): 父级ID (0=顶级)
        
        约束条件：
        - 只生成 SELECT 查询语句，禁止 INSERT/UPDATE/DELETE
        - 只查询上述表，不要查询其他表
        - 适当使用 JOIN 关联多表查询
        - 使用 LIMIT 限制返回行数（默认100）
        
        用户问题: {question}
        
        请直接返回SQL语句，不需要任何解释、代码块标记或其他文字。
        """;

    // ========================
    // 5. DB查询结果解读 Prompt
    // ========================
    /**
     * 数据库查询结果解读提示词
     * 让 LLM 将 SQL 查询结果转化为用户友好的自然语言回答
     */
    public static final String DB_QUERY_ANSWER = """
        请根据数据库查询结果，用自然语言回答用户的问题。
        
        【用户原始问题】:
        {question}
        
        【查询结果】:
        {result}
        
        回答要求：
        1. 用通俗易懂的语言解读数据
        2. 如果是列表数据，用表格或列表形式展示
        3. 如果是统计数据，给出明确的数字和简要分析
        4. 如果查询结果为空，说明没有找到匹配的数据
        """;

    private PromptTemplates() {
        // 工具类，禁止实例化
    }
}
