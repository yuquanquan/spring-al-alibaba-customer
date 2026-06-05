# Spring AI Alibaba 智能客服系统

> 基于 Spring AI Alibaba 的 RAG 智能客服全链路学习项目  
> 版本: V1.0 | 最后更新: 2026-06-03

---

## 📚 项目概述

本项目是一个企业级智能客服系统，涵盖从意图识别到最终回答生成的完整 RAG（检索增强生成）链路。

**核心能力：**
- ✅ 意图识别：自动判断用户意图（闲聊 / 知识库检索 / 数据库查询）
- ✅ RAG 检索增强生成：Query改写 → 多路召回 → 元数据过滤 → 上下文增强回答
- ✅ NL2SQL：自然语言转 SQL，查询业务数据（用户/订单/权限）
- ✅ 多轮对话记忆：短期记忆（内存）+ 长期记忆（PostgreSQL）+ 记忆压缩
- ✅ 文档生成：自动生成 Word 订单说明书、带图片的 PDF 退货说明书

**技术栈：**
| 组件 | 技术选型 |
|------|---------|
| 框架 | Spring Boot 3.5.4 + Spring AI 1.1.2 |
| AI引擎 | 阿里云 DashScope (通义千问 qwen-plus) |
| 向量数据库 | PostgreSQL + pgvector (HNSW 索引) |
| 业务数据库 | PostgreSQL + Spring Data JPA |
| 文档生成 | Apache POI (Word) + iText (PDF) |

---

## 🚀 快速开始

### 1. 环境准备

```bash
# 需要安装
- JDK 17+
- PostgreSQL 15+ (需安装 pgvector 扩展)
- Maven 3.6+
- 阿里云 DashScope API Key
```

### 2. 数据库配置

```sql
-- 创建数据库
CREATE DATABASE smart_cs;

-- 启用 pgvector 扩展
CREATE EXTENSION IF NOT EXISTS vector;
```

### 3. 修改配置

编辑 `src/main/resources/application.yml`：

```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/smart_cs
    username: postgres
    password: your_password

  ai:
    dashscope:
      api-key: ${DASHSCOPE_API_KEY:your_api_key_here}
```

### 4. 启动应用

```bash
mvn spring-boot:run
```

### 5. 初始化知识库

```bash
curl -X POST http://localhost:8080/api/docs/init
```

### 6. 测试对话

```bash
# 闲聊
curl -X POST http://localhost:8080/api/chat \
  -H "Content-Type: application/json" \
  -d '{"question": "你好", "sessionId": "user001"}'

# RAG 知识库查询
curl -X POST http://localhost:8080/api/chat \
  -H "Content-Type: application/json" \
  -d '{"question": "怎么退货?", "sessionId": "user001"}'

# 数据库查询
curl -X POST http://localhost:8080/api/chat \
  -H "Content-Type: application/json" \
  -d '{"question": "查看所有订单", "sessionId": "user001"}'
```

---

## 📖 学习路线图

### 🔥 第一阶段：RAG 基础（已完成）

- [x] 意图识别（LLM + BeanOutputParser）
- [x] Query 改写（多维度改写策略）
- [x] 文档去噪（正则 + 内容过滤）
- [x] 文档切块（TokenTextSplitter）
- [x] 元数据过滤（FilterExpressionBuilder）
- [x] 多路召回（原始 + 改写 + 子查询）
- [x] NL2SQL（自然语言转 SQL）

### 🔥 第二阶段：高级特性（进行中）

- [x] **多轮对话记忆**（短期 + 长期 + 压缩）← 刚完成
- [ ] Function Calling / Tool Use
- [ ] 流式输出优化（真正的 SSE 流式）
- [ ] BM25 混合检索实现
- [ ] Reranker 集成（Cohere/BGE）

### ⏳ 第三阶段：Agent 框架（待学习）

- [ ] **Spring AI Alibaba Agent Framework** ← 重点
  - ReAct 模式理解
  - Planner-Executor 架构
  - 多 Agent 协作
  - 工具注册与发现
- [ ] MCP（Model Context Protocol）
- [ ] Guardrails（安全护栏）
- [ ] 评估框架（RAGAS/TruLens）

---

## 🏗️ 项目结构

```
springaialibaba/
├── pom.xml
├── src/main/
│   ├── java/com/example/smartcs/
│   │   ├── SmartCsApplication.java
│   │   ├── config/
│   │   │   ├── AiConfig.java                  # AI 配置
│   │   │   ├── PromptTemplates.java           # 提示词模板
│   │   │   └── ChatMemoryConfig.java          # 对话记忆配置 ✨新增
│   │   ├── model/                             # 模型类
│   │   ├── entity/                            # 业务实体
│   │   │   ├── User.java / Order.java / ...
│   │   │   └── ChatHistory.java               # 对话历史实体 ✨新增
│   │   ├── repository/                        # 数据访问
│   │   │   └── ChatHistoryRepository.java     # 对话历史 Repository ✨新增
│   │   ├── service/                           # 核心服务
│   │   │   ├── IntentRecognizer.java          # 意图识别
│   │   │   ├── QueryRewriter.java             # Query 改写
│   │   │   ├── DocumentRetriever.java         # 多路召回
│   │   │   ├── DatabaseQueryService.java      # NL2SQL
│   │   │   ├── ChatService.java               # 核心编排（已集成记忆）✨更新
│   │   │   ├── ChatMemoryService.java         # 对话记忆服务 ✨新增
│   │   │   ├── DocumentEtlPipeline.java       # ETL 管道
│   │   │   └── DocumentGenerator.java         # 文档生成
│   │   └── controller/                        # API 接口
│   │       ├── ChatController.java            # 对话接口（支持 sessionId）✨更新
│   │       └── DocumentController.java        # 文档管理
│   └── resources/
│       ├── application.yml
│       ├── db/schema.sql                      # 建表脚本（含 chat_history）✨更新
│       └── knowledge-base/                    # 知识库文档
│           ├── product-faq.md
│           ├── return-policy.md
│           ├── product-manual.md
│           └── project-guide.md               # 项目学习说明书
└── README.md                                  # 本文件
```

---

## 🎯 核心功能详解

### 1. 多轮对话记忆（新）

**架构设计：**
```
短期记忆（内存）          长期记忆（PostgreSQL）
┌─────────────┐          ┌──────────────────┐
│最近10条消息  │ ←同步→  │chat_history 表    │
│快速访问      │          │跨会话持久化       │
└─────────────┘          └────────┬─────────┘
                                  │
                          ┌───────┴────────┐
                          │ 记忆压缩        │
                          │ >20条消息时触发  │
                          │ LLM总结对话     │
                          └────────────────┘
```

**使用示例：**
```java
// 第一轮对话
POST /api/chat
{
  "question": "我想查订单",
  "sessionId": "user001"
}
AI: "请问您的用户名是什么？"

// 第二轮对话（AI 记得上一轮）
POST /api/chat
{
  "question": "张三",
  "sessionId": "user001"  // ← 相同 sessionId
}
AI: "张三您好，您的订单如下..."  // ← AI 知道"张三"和"订单"的关系
```

**记忆压缩机制：**
- 当未压缩消息数 ≥ 20 条时触发
- 调用 LLM 生成对话总结
- 用 1 条总结替代 20 条原始消息
- Token 消耗从 ~5000 降至 ~200

---

## 📊 API 接口汇总

| 方法 | 路径 | 功能 |
|------|------|------|
| POST | `/api/chat` | 智能对话（带记忆） |
| POST | `/api/chat/filter` | 带文档类型过滤的对话 |
| GET  | `/api/chat/stream` | 流式对话（SSE） |
| POST | `/api/docs/init` | 初始化知识库 |
| GET  | `/api/docs/search` | 搜索知识库 |
| GET  | `/api/docs/download/order-manual` | 下载订单说明书(Word) |
| GET  | `/api/docs/download/return-manual` | 下载退货说明书(PDF) |

---

## 🎓 面试考点整理

### Q1: PgVector vs Elasticsearch 做向量库的优势？

**回答要点：**
1. **ACID 事务一致性**：单数据库搞定业务+向量，避免双写同步问题
2. **运维成本低**：DBA 熟悉 PG，不需要专门 ES 运维团队
3. **开发效率高**：SQL 即可操作，不需要学习 ES DSL

**临床医疗场景示例：**
> 患者出院时需要原子性删除所有数据（病历+向量），PgVector 可在一个事务中完成，ES 需要额外补偿机制防止隐私泄露。

### Q2: 如何实现混合检索（向量 + BM25）？

**回答要点：**
1. 手动实现 BM25 算法（倒排索引 + IDF/TF 计算）
2. 分别执行向量检索和 BM25 检索
3. 使用 RRF（Reciprocal Rank Fusion）融合两种结果

### Q3: 50万向量对应多少文档？

**估算公式：** 文档数 = 向量数 / 每文档切块数

| 文档类型 | 平均每文档切块数 | 50万向量对应文档数 |
|---------|----------------|------------------|
| FAQ 问答 | 1~2 块 | 25万~50万文档 |
| 产品手册 | 5~10 块 | 5万~10万文档 |
| 法律合同 | 20~50 块 | 1万~2.5万文档 |

---

## 📝 后续学习计划

### Agent 框架（优先级最高）

**学习目标：**
1. 理解 ReAct 模式（Reasoning + Acting）
2. 掌握 Planner-Executor 架构
3. 实现多 Agent 协作（客服 Agent + 订单 Agent）
4. 工具注册与动态发现

**推荐资源：**
- [Spring AI Alibaba Agent Framework 官方文档](https://spring-ai-alibaba.io/docs/agent-framework)
- LangChain Agent 设计理念对比
- AutoGen 多智能体协作案例

**预计学习时间：** 2周

---

## 🤝 贡献指南

欢迎提交 Issue 和 Pull Request！

---

## 📄 License

MIT License
