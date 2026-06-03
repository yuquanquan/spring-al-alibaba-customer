package com.example.smartcs.model;

/**
 * 意图类型枚举
 * <p>
 * 意图识别是智能客服的第一道关卡。
 * 通过LLM对用户输入进行分类，决定后续走哪条处理链路：
 * <ul>
 *   <li>CHAT     → 直接由LLM回复（闲聊/问候）</li>
 *   <li>RAG      → 知识库检索增强生成（产品问题/退货政策等）</li>
 *   <li>DB_QUERY → 自然语言转SQL查询业务数据（用户/订单/权限）</li>
 * </ul>
 */
public enum IntentType {
    /** 闲聊/问候/通用对话 */
    CHAT("闲聊"),
    /** RAG知识库检索增强生成 */
    RAG("知识库查询"),
    /** 数据库查询（用户/订单/权限） */
    DB_QUERY("数据库查询");

    private final String description;

    IntentType(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }
}
