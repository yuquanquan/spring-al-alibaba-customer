# Spring AI Alibaba 智能客服系统

> 基于 Spring AI Alibaba 的企业级 RAG 智能客服全链路项目  
> 版本: V2.0 

---

## 项目概述

企业级智能客服系统，覆盖 RAG 全链路 + Function Calling + 两层记忆架构 + 自适应重排序。

**核心能力：**

| 能力 | 说明 |
|------|------|
| 意图识别 | LLM 分类 + 关键词快速路径，路由到 CHAT / RAG / DB_QUERY |
| RAG 检索增强 | Query改写 → 多路混合检索 → RRF分数融合 → 三级去重 → 自适应排序 |
| Function Calling | @Tool 薄代理架构，80%预定义工具 + 20%受控NL2SQL |
| 两层记忆 | Redis 滑动窗口（短期）+ 结构化画像（长期），异步事件驱动 |
| 混合检索 | 向量检索 + BM25 + RRF 融合，多路并行 |
| 自适应重排序 | <500文档用RRF分数，≥500文档调 gte-rerank 交叉编码器 |
| NL2SQL 安全 | JSqlParser AST 白名单校验（表名/列名/操作类型） |
| 文档生成 | Apache POI (Word) + iTextPDF (PDF) |

---

## 技术栈

| 组件 | 选型 | 版本 |
|------|------|------|
| 框架 | Spring Boot + Spring AI | 3.5.4 / 1.1.2 |
| AI 引擎 | 阿里云 DashScope | 通义千问 qwen3.6-plus |
| 向量数据库 | PostgreSQL + pgvector (HNSW) | 15+ |
| 业务数据库 | PostgreSQL + Spring Data JPA | 15+ |
| 缓存 | Redis (记忆 + 画像缓存) | 7+ |
| Embedding | text-embedding-v3 (1024维) | DashScope |
| Rerank | gte-rerank (交叉编码器) | DashScope |
| SQL 校验 | JSqlParser | 5.0 |
| 文档处理 | Apache POI + iTextPDF + PDFBox + Tika | - |

---

## 系统架构

```
用户输入
   │
   ▼
┌──────────────┐
│  意图识别      │  IntentRecognizer (LLM + 关键词快速路径)
└──────┬───────┘
       │
  ┌────┴──────────────────────────────┐
  │           │                        │
  ▼ CHAT      ▼ RAG                    ▼ DB_QUERY
┌────────┐  ┌─────────────────┐     ┌─────────────────┐
│ 直接回复 │  │ Query改写         │     │ Function Calling │
└────────┘  │ (改写+子查询)     │     │ .tools(DB工具)   │
            └───────┬─────────┘     └───────┬─────────┘
                    │                        │
            ┌───────┴─────────┐     ┌───────┴─────────┐
            │ 多路混合检索      │     │ 预定义工具(80%)   │
            │ 向量+BM25+RRF   │     │ 受控NL2SQL(20%)  │
            └───────┬─────────┘     └───────┬─────────┘
                    │                        │
            ┌───────┴─────────┐              │
            │ 三级去重 + 排序   │              │
            │ RRF分数/Rerank  │              │
            └───────┬─────────┘              │
                    │                        │
                    ▼                        ▼
            ┌─────────────────────────────────┐
            │  LLM 生成回答（注入记忆+上下文）   │
            └─────────────────────────────────┘
                    │
            ┌───────┴───────┐
            │ 异步记忆持久化  │  MemoryPersistEvent → @Async
            │ 事实提取/画像  │  FactExtractor → UserFact
            └───────────────┘
```

---

## 项目结构

```
src/main/java/com/example/smartcs/
├── SmartCsApplication.java
├── config/
│   ├── AiConfig.java                  # ChatClient / EmbeddingModel 配置
│   ├── AsyncConfig.java               # @Async 异步线程池配置
│   ├── ChatMemoryConfig.java          # Redis 滑动窗口记忆配置
│   ├── PromptTemplates.java           # 所有 Prompt 模板
│   ├── RedisChatMemoryRepository.java # Redis 记忆存储实现
│   └── SqlSecurityValidator.java      # JSqlParser AST 白名单校验
├── controller/
│   ├── ChatController.java            # 对话 API（同步 + SSE 流式）
│   └── DocumentController.java        # 文档管理 API
├── entity/
│   ├── User.java / Order.java / ...   # 业务实体
│   ├── ChatHistory.java               # 对话历史
│   └── UserFact.java                  # 画像事实（Layer 2 记忆）
├── event/
│   └── MemoryPersistEvent.java        # 记忆持久化事件
├── listener/
│   └── MemoryPersistListener.java     # @Async 事件监听器
├── model/                             # DTO / Record 类
├── repository/                        # Spring Data JPA
├── search/
│   ├── BM25SearchEngine.java          # BM25 倒排索引实现
│   ├── HybridSearchService.java       # 向量+BM25 混合检索 + RRF 融合
│   └── RerankService.java             # DashScope gte-rerank 重排序
├── service/
│   ├── ChatService.java               # 核心编排（意图路由+全链路串联）
│   ├── ChatMemoryService.java         # 记忆管理服务
│   ├── IntentRecognizer.java          # 意图识别
│   ├── QueryRewriter.java             # Query 改写（多维度策略）
│   ├── DocumentRetriever.java         # 多路召回 + 分数追踪 + 去重 + 排序
│   ├── DatabaseQueryService.java      # 业务查询逻辑层（唯一业务代码位置）
│   ├── DatabaseTools.java             # @Tool 薄代理层（零业务代码）
│   ├── DocumentEtlPipeline.java       # 文档 ETL 管道（解析→清洗→切块→存储）
│   ├── DocumentGenerator.java         # 文档生成（Word / PDF）
│   └── FactExtractor.java             # 画像事实提取（LLM → 结构化事实）
└── resources/
    ├── application.yml                # 完整配置
    ├── db/schema.sql                  # 建表脚本
    └── knowledge-base/                # 知识库文档
```

---

## 核心功能详解

### 1. 混合检索 + RRF 融合 + 自适应重排序

```
每路 hybridSearch 内部:
  向量检索 ──┐
            ├──→ RRF 融合 → rrfScore 存入 metadata
  BM25 检索 ──┘

多路合并:
  原始查询 ──→ scoreMap[docId] += rrfScore
  改写查询 ──→ scoreMap[docId] += rrfScore  (累加)
  子查询   ──→ scoreMap[docId] += rrfScore

三级去重: ID去重 → 精确去重 → Jaccard去重(条件触发)

自适应排序:
  知识库 < 500 篇 → 按 RRF 累加分数降序（零成本）
  知识库 ≥ 500 篇 → gte-rerank 交叉编码器精排（~200ms）
  Rerank 失败    → 自动降级为 RRF 排序
```

### 2. Function Calling 薄代理架构

```
Controller（HTTP入口）──→ ChatService ──→ DatabaseTools（@Tool 薄代理）
                                              │ 一行委托
                                              ↓
                                        DatabaseQueryService（业务逻辑层）
                                              │ SQL 执行
                                              ↓
                                        JdbcTemplate
```

- DatabaseTools：只有 `@Tool` 注解 + `return databaseQueryService.xxx()`
- DatabaseQueryService：所有 SQL、业务逻辑、格式化都在这一层
- Controller 和 Tool 是平级入口，共享同一套 Service

### 3. 两层记忆架构（Redis 版）

```
Layer 1: 工作记忆（Redis 滑动窗口）
  Key: chat:memory:{sessionId}
  最近 10 条消息 → messages() 注入

Layer 2: 画像记忆（结构化事实）
  Key: chat:profile:{sessionId} (Redis Hash)
  user_name=张三, topic=退货 → system() 注入
  持久化: PostgreSQL user_fact 表
```

- 短期同步写 Redis（< 1ms）
- 长期异步处理（MemoryPersistEvent → @Async → FactExtractor）
- 画像恒定 ~200 token，不随对话轮数增长

### 4. NL2SQL 安全校验

```
SQL 生成 → JSqlParser 解析 AST → 白名单校验
  ├─ 表名白名单: sys_user / sys_order / sys_order_item / sys_permission
  ├─ 列黑名单: password / secret / token
  ├─ 操作白名单: 只允许 SELECT
  └─ 递归子查询校验
```

---

## 快速开始

### 环境要求

- JDK 17+
- PostgreSQL 15+ (需 pgvector 扩展)
- Redis 7+
- Maven 3.6+
- DashScope API Key

### 配置

编辑 `src/main/resources/application.yml`：

```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/smart_cs
  data:
    redis:
      host: localhost
  ai:
    dashscope:
      api-key: ${DASHSCOPE_API_KEY}
```

### 启动

```bash
mvn spring-boot:run
```

### 初始化知识库

```bash
curl -X POST http://localhost:8080/api/docs/init
```

### 测试

```bash
# 闲聊
curl -X POST http://localhost:8080/api/chat \
  -H "Content-Type: application/json" \
  -d '{"question": "你好", "sessionId": "user001"}'

# RAG 知识库查询
curl -X POST http://localhost:8080/api/chat \
  -H "Content-Type: application/json" \
  -d '{"question": "怎么退货?", "sessionId": "user001"}'

# 数据库查询（Function Calling）
curl -X POST http://localhost:8080/api/chat \
  -H "Content-Type: application/json" \
  -d '{"question": "张三的订单有哪些?", "sessionId": "user001"}'
```

---

## API 接口

| 方法 | 路径 | 功能 |
|------|------|------|
| POST | `/api/chat` | 智能对话（意图路由 + 记忆） |
| POST | `/api/chat/filter` | 带文档类型过滤的 RAG 对话 |
| GET  | `/api/chat/stream` | SSE 流式对话 |
| POST | `/api/docs/init` | 初始化知识库 |
| GET  | `/api/docs/search` | 搜索知识库 |
| GET  | `/api/docs/download/order-manual` | 下载订单说明书 (Word) |
| GET  | `/api/docs/download/return-manual` | 下载退货说明书 (PDF) |

---

## 学习路线图

### 第一阶段：RAG 基础 ✅

- [x] 意图识别（LLM + BeanOutputParser + 关键词快速路径）
- [x] Query 改写（语义保持 + 关键词扩展 + 查询分解）
- [x] 文档 ETL（Tika 解析 → 正则去噪 → Parent-Child 切块）
- [x] 多路召回（原始 + 改写 + 子查询 三路并行）
- [x] 元数据过滤（FilterExpressionBuilder）

### 第二阶段：高级特性 ✅

- [x] 混合检索（向量 + BM25 + RRF 融合）
- [x] 三级级联去重（ID → 精确 → Jaccard）
- [x] 自适应重排序（RRF 分数 / gte-rerank 交叉编码器）
- [x] 多轮对话记忆（Redis 滑动窗口 + 画像事实）
- [x] 异步记忆持久化（事件驱动 + @Async）
- [x] Function Calling（@Tool 薄代理架构）
- [x] NL2SQL 安全校验（JSqlParser AST 白名单）

### 第三阶段：Agent 框架（计划中）

- [ ] Spring AI Alibaba Agent Framework（ReAct / Planner-Executor）
- [ ] MCP（Model Context Protocol）
- [ ] 多 Agent 协作
- [ ] Guardrails 安全护栏
- [ ] 评估框架（RAGAS / TruLens）

---




