package com.example.smartcs;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 智能客服系统主启动类
 * <p>
 * 基于 Spring AI Alibaba 构建的 RAG 智能客服系统，核心能力包括：
 * <ul>
 *   <li><b>意图识别</b>: 自动判断用户意图（闲聊 / 知识库检索 / 数据库查询）</li>
 *   <li><b>RAG检索增强生成</b>: Query改写 → 多路召回 → 元数据过滤 → 上下文增强</li>
 *   <li><b>NL2SQL</b>: 将自然语言转换为SQL查询业务数据（用户/订单/权限）</li>
 *   <li><b>文档生成</b>: 自动生成Word订单说明书、带图片的PDF退货说明书</li>
 * </ul>
 */
@SpringBootApplication
public class SmartCsApplication {

    public static void main(String[] args) {
        SpringApplication.run(SmartCsApplication.class, args);
    }
}
