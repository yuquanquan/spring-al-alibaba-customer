package com.example.smartcs.config;

import com.example.smartcs.model.ChatIntent;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * AI 配置中心
 * <p>
 * 集中管理所有 AI 相关的 Bean 配置：
 * - ChatClient: 统一的聊天客户端（含系统提示词）
 * - OutputConverter: 结构化输出转换器（意图识别用）
 * - DocumentDenoiser: 文档去噪转换器（RAG ETL用）
 */
@Configuration
public class AiConfig {

    /**
     * 全局ChatClient配置
     * <p>
     * 设置默认的系统提示词，定义AI的角色和能力边界。
     * 所有通过此Builder创建的ChatClient都会继承这个系统提示词。
     * <p>
     * 【提示词工程要点】:
     * 1. 明确角色定义（"你是..."）
     * 2. 列出能力边界（"你能做什么"）
     * 3. 设置回复风格（"专业且友好"）
     */
    @Bean
    public ChatClient chatClient(ChatClient.Builder builder) {
        return builder
            .defaultSystem("""
                你是一个专业的智能客服助手，服务于"智能客服系统"产品。
                
                你的核心能力：
                1. 回答关于产品功能、使用方法、价格等知识库问题
                2. 查询用户的订单信息、用户信息、权限信息等业务数据
                3. 进行友好的日常对话和问候
                
                回复规则：
                - 始终使用中文回复
                - 回复要准确、专业、友好
                - 如果不确定答案，请诚实告知用户，不要编造信息
                - 对于涉及具体数据的问题，务必基于查询结果回答
                """)
            .build();
    }

    /**
     * 意图识别的结构化输出解析器
     * <p>
     * BeanOutputConverter 利用 LLM 的 Function Calling / JSON Mode 能力，
     * 将LLM的自由文本输出自动转换为Java对象（ChatIntent）。
     * <p>
     * 原理：
     * 1. 根据 ChatIntent 的字段定义生成 JSON Schema
     * 2. 将 Schema 注入到 Prompt 中，指导 LLM 输出指定格式
     * 3. 自动将 LLM 的 JSON 输出反序列化为 ChatIntent 对象
     */
    @Bean
    public BeanOutputConverter<ChatIntent> intentOutputParser() {
        return new BeanOutputConverter<>(ChatIntent.class);
    }
}
