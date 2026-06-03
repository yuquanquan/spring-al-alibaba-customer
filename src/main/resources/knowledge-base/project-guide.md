# 智能客服系统 - 项目学习说明书

> 基于 Spring AI Alibaba 的 RAG 智能客服全链路学习项目
> 版本: V1.0

---

## 一、项目概述

本项目是一个基于 **Spring AI Alibaba** 构建的智能客服系统，涵盖了从意图识别到最终回答生成的完整 RAG（检索增强生成）链路。

**核心能力：**
- 意图识别：自动判断用户意图（闲聊 / 知识库检索 / 数据库查询）
- RAG 检索增强生成：Query改写 → 多路召回 → 元数据过滤 → 上下文增强回答
- NL2SQL：自然语言转 SQL，查询业务数据（用户/订单/权限）
- 文档生成：自动生成 Word 订单说明书、带图片的 PDF 退货说明书

**技术栈：**
| 组件 | 技术选型 | 说明 |
|------|---------|------|
| 框架 | Spring Boot 3.5.4 | 基础框架 |
| AI引擎 | Spring AI 1.1.2 + Alibaba 1.1.2.2 | AI 能力层 |
| 大模型 | 阿里云 DashScope (通义千问 qwen-plus) | LLM + Embedding |
| 向量数据库 | PostgreSQL + pgvector | HNSW 索引，余弦相似度 |
| 业务数据库 | PostgreSQL + Spring Data JPA | 用户/订单/权限 |
| 文档生成 | Apache POI (Word) + iText (PDF) | 业务文档输出 |

---

## 二、项目结构总览

```
springaialibaba/
├── pom.xml                                    # Maven 依赖配置
├── src/main/
│   ├── java/com/example/smartcs/
│   │   ├── SmartCsApplication.java            # 主启动类
│   │   │
│   │   ├── config/                            # ====== 配置层 ======
│   │   │   ├── AiConfig.java                  #   AI配置 (ChatClient + OutputParser)
│   │   │   └── PromptTemplates.java           #   提示词模板 (5套Prompt)
│   │   │
│   │   ├── model/                             # ====== 模型层 ======
│   │   │   ├── IntentType.java                #   意图枚举 (CHAT/RAG/DB_QUERY)
│   │   │   ├── ChatIntent.java                #   意图识别结果 (Record)
│   │   │   ├── QueryRewriteResult.java        #   Query改写结果 (Record)
│   │   │   └── RetrievedContext.java          #   检索上下文 (Record)
│   │   │
│   │   ├── entity/                            # ====== 业务实体层 ======
│   │   │   ├── User.java                      #   用户表实体
│   │   │   ├── Order.java                     #   订单表实体
│   │   │   ├── OrderItem.java                 #   订单明细实体
│   │   │   └── Permission.java                #   权限表实体
│   │   │
│   │   ├── repository/                        # ====== 数据访问层 ======
│   │   │   ├── UserRepository.java            #   用户 Repository
│   │   │   ├── OrderRepository.java           #   订单 Repository
│   │   │   └── PermissionRepository.java      #   权限 Repository
│   │   │
│   │   ├── service/                           # ====== 核心服务层 ======
│   │   │   ├── IntentRecognizer.java          #   ★ 意图识别 (LLM分类)
│   │   │   ├── QueryRewriter.java             #   ★ Query改写 (多维度改写)
│   │   │   ├── DocumentRetriever.java         #   ★ 多路召回 + 元数据过滤
│   │   │   ├── DatabaseQueryService.java      #   ★ NL2SQL (自然语言→SQL)
│   │   │   ├── ChatService.java               #   ★ 核心编排 (路由+串联全链路)
│   │   │   ├── DocumentEtlPipeline.java       #   ★ ETL管道 (去噪→切块→入库)
│   │   │   └── DocumentGenerator.java         #   ★ 文档生成 (Word/PDF)
│   │   │
│   │   └── controller/                        # ====== API 接口层 ======
│   │       ├── ChatController.java            #   对话接口 (同步 + SSE流式)
│   │       └── DocumentController.java        #   文档管理接口
│   │
│   └── resources/
│       ├── application.yml                    # 应用配置 (数据库/AI/向量库)
│       ├── db/
│       │   └── schema.sql                     # 建表脚本 + 示例数据
│       └── knowledge-base/                    # 知识库文档 (Markdown)
│           ├── product-faq.md                 #   产品FAQ
│           ├── return-policy.md               #   退货政策
│           └── product-manual.md              #   产品手册
```

---

## 三、核心链路与学习要点

### 3.1 完整处理流程

```.
                         用户输入
                            │
                            ▼
                  ┌───────────────────┐
                  │   意图识别          │  ← IntentRecognizer (LLM + BeanOutputParser)
                  │   (判断用户意图)    │
                  └─────────┬─────────┘
                            │
            ┌───────────────┼───────────────┐
            ▼               ▼               ▼
         CHAT             RAG           DB_QUERY
       (闲聊问候)     (知识库查询)     (数据库查询)
            │               │               │
            ▼               ▼               ▼
      ┌──────────┐  ┌──────────────┐  ┌──────────┐
      │ LLM直接   │  │  Query改写    │  │  NL2SQL  │
      │ 回复      │  │  (多维度改写) │  │ (生成SQL) │
      └──────────┘  └──────┬───────┘  └────┬─────┘
                           │               │
                           ▼               ▼
                    ┌──────────────┐  ┌──────────┐
                    │  多路召回     │  │ 执行查询  │
                    │ (向量检索×3) │  │ (JDBC)   │
                    └──────┬───────┘  └────┬─────┘
                           │               │
                           ▼               ▼
                    ┌──────────────┐  ┌──────────┐
                    │ 上下文注入    │  │ 结果解读  │
                    │ LLM生成回答  │  │ LLM生成   │
                    └──────────────┘  └──────────┘
```

---

### 3.2 意图识别

**对应文件：** `service/IntentRecognizer.java` + `config/PromptTemplates.java`

**学习要点：**
- 意图识别是智能客服的**第一道关卡**，决定请求走哪条处理链路
- 采用 **LLM-Based** 方案：将用户输入 + 意图定义 + Few-Shot示例 注入 Prompt
- 使用 `BeanOutputParser<ChatIntent>` 将 LLM 输出自动解析为 Java 结构化对象
- 内置**快速路径**：关键词匹配跳过 LLM 调用（如"你好"直接返回 CHAT）

**Prompt 设计核心：**
```
1. 明确列出所有意图类型及判断标准
2. 每种意图给出典型示例（Few-Shot）
3. 要求输出置信度和推理过程（思维链 CoT）
4. 使用 BeanOutputParser 约束 JSON 输出格式
```

**调用示例：**
```java
ChatIntent intent = intentRecognizer.recognize("怎么退货?");
// → ChatIntent(intentType=RAG, confidence=0.95, reason="用户询问退货流程...")
```

---

### 3.3 提示词工程

**对应文件：** `config/PromptTemplates.java`

**学习要点：** 本项目包含 **5 套精心设计的 Prompt 模板**：

| 模板名称 | 用途 | 核心技巧 |
|---------|------|---------|
| `INTENT_RECOGNITION` | 意图识别 | Few-Shot示例 + CoT思维链 + 结构化输出 |
| `QUERY_REWRITE` | Query改写 | 多维度改写策略 + JSON格式约束 |
| `RAG_ANSWER` | RAG回答生成 | 上下文注入 + 诚实性约束 + 结构化输出 |
| `NL2SQL` | 自然语言转SQL | 表结构注入 + 安全约束 + 只允许SELECT |
| `DB_QUERY_ANSWER` | 查询结果解读 | 数据→自然语言 + 表格格式化 |

**Prompt 工程五大原则：**
1. **明确任务**：清晰告诉 LLM 要做什么
2. **提供上下文**：给出必要的背景信息和数据
3. **指定格式**：要求输出特定格式（JSON/Markdown）
4. **Few-Shot示例**：提供输入输出示例帮助 LLM 理解
5. **约束条件**：设置边界（不要编造、不要超出范围）

---

### 3.4 Query 改写

**对应文件：** `service/QueryRewriter.java`

**学习要点：**
Query 改写是 RAG 链路中**提升召回率**的关键技术。

**为什么需要改写？**
- 用户表述模糊："那个怎么弄" → 语义不明确
- 口语化表达："咋退钱" → 知识库中写的是"退货退款流程"
- 复合问题："功能和价格" → 一个向量无法同时匹配两个主题

**改写策略：**
| 策略 | 说明 | 示例 |
|------|------|------|
| 语义保持改写 | 换表述方式，保持语义不变 | "咋退货" → "退货流程是什么" |
| 关键词扩展 | 添加同义词/专业术语 | "退货" → "退货退款 售后 返还" |
| 查询分解 | 复合问题拆分为子问题 | "功能和价格" → ["功能介绍", "价格方案"] |

---

### 3.5 去噪 (Denoising)

**对应文件：** `service/DocumentEtlPipeline.java` → `denoise()` 方法

**学习要点：**
原始文档中包含大量"噪声"，会影响向量检索精度。

**去噪策略：**
```
1. 移除页码:         "第1页"、"Page 1" → 正则替换删除
2. 合并连续空白行:   



 → 


3. 去除多余空格:     多个空格 → 单个空格
4. 裁剪首尾空白:     trim()
5. 过滤过短片段:     length < 20 的文档块 → 丢弃（大概率是噪声）
```

**进阶方向：**
- 使用 LLM 进行智能摘要/去噪
- 移除 HTML 标签、特殊字符
- 标准化标点符号

---

### 3.6 切块 (Chunking)

**对应文件：** `service/DocumentEtlPipeline.java` → `splitDocuments()` 方法

**学习要点：**
将长文档切分为适当大小的"块"（Chunk），每块是向量检索的基本单元。

**切块策略对比：**

| 策略 | 原理 | 优点 | 缺点 |
|------|------|------|------|
| 固定大小切块 | 每N个token切一块 | 简单可控 | 可能截断语义 |
| 语义切块 | 按段落/章节切分 | 保留语义完整 | 块大小不均匀 |
| 滑动窗口 | 相邻块有重叠 | 上下文连贯 | 存储开销大 |

**本项目使用 TokenTextSplitter（基于Token的智能切块）：**
```
参数说明:
- chunkSize = 800:        每块约800个token
- minChunkSize = 350:     最小350字符（太小则合并到相邻块）
- minChunkLength = 50:    最短50字符才会被嵌入向量
- keepSeparator = true:   保留分隔符（在段落/句子边界切分）
```

**调优建议：**
- chunkSize 太大 → 检索精度低（一个向量包含太多信息）
- chunkSize 太小 → 上下文不完整（回答缺乏连贯性）
- 建议从 500~1000 开始，根据实际效果调整

---

### 3.7 元数据过滤 (Metadata Filtering)

**对应文件：** `service/DocumentRetriever.java` → `retrieveWithFilter()` 方法

**学习要点：**
元数据过滤是在向量检索基础上，增加结构化过滤条件，精准定位文档。

**工作原理：**
```
每个文档块在入库时附带元数据:
{
  "source": "return-policy.md",    // 文档来源
  "docType": "return-policy",      // 文档类型
  "chunkIndex": 3,                 // 块序号
  "totalChunks": 15                // 总块数
}

检索时可按元数据过滤:
- 只在 docType="faq" 中搜索 → 精确匹配FAQ类文档
- 只在 source="return-policy.md" 中搜索 → 限定退货政策文档
```

**Spring AI FilterExpressionBuilder 语法：**
```java
FilterExpressionBuilder builder = new FilterExpressionBuilder();
// 等于
builder.eq("docType", "faq").build();
// 包含
builder.in("docType", "faq", "return-policy").build();
// 组合
builder.and(
    builder.eq("docType", "faq"),
    builder.eq("source", "product-faq.md")
).build();
```

---

### 3.8 索引选择 (Index Selection)

**对应文件：** `application.yml` → `spring.ai.vectorstore.pgvector` 配置

**学习要点：** PgVector 支持两种向量索引：

| 索引类型 | IVFFlat | HNSW (本项目选用) |
|---------|---------|-------------------|
| 全称 | Inverted File Flat | Hierarchical Navigable Small World |
| 原理 | 将向量空间划分为多个簇 | 构建多层图结构 |
| 查询 | 只搜索最近的几个簇 | 从顶层快速定位，逐层细化 |
| 构建速度 | 快 | 慢 |
| 查询精度 | 一般（需足够数据量） | 高（小数据量也精准） |
| 内存占用 | 小 | 较大 |
| 适用场景 | 数据量 > 100K | 数据量 < 100K |

**本项目配置：**
```yaml
spring.ai.vectorstore.pgvector:
  dimensions: 1024          # 嵌入维度（须与嵌入模型一致）
  index-type: hnsw          # HNSW 索引
  distance-type: cosine     # 余弦相似度
  initialize-schema: true   # 自动建表
```

---

### 3.9 多路召回 (Multi-Way Recall)

**对应文件：** `service/DocumentRetriever.java` → `multiWayRetrieve()` 方法

**学习要点：**
多路召回 = 使用多种检索策略同时搜索 → 合并去重 → 提高召回率。

**本项目的三路召回策略：**
```
路径1: 原始查询 ──────→ 向量检索 (TopK=5)
路径2: 改写查询(×N) ──→ 向量检索 (TopK=3, 每个版本)
路径3: 子查询(×M) ────→ 向量检索 (TopK=3, 每个子问题)
                              │
                              ▼
                    ┌─────────────────┐
                    │  LinkedHashMap  │  ← 按文档ID去重，保持插入顺序
                    │  合并去重       │
                    └─────────────────┘
```

**为什么多路召回比单次检索好？**
- 原始查询可能表述不精确 → 改写版本补充语义
- 复合问题单次检索效果差 → 子查询分别检索
- 不同表述可能命中不同文档 → 合并后覆盖面更广

---

### 3.10 ETL 管道 (Extract-Transform-Load)

**对应文件：** `service/DocumentEtlPipeline.java`

**学习要点：** ETL 是 RAG 系统的数据准备阶段，决定知识库质量。

**完整流程：**
```
原始文档(MD/PDF/Word)
    │
    ▼ [Extract] TextReader / TikaDocumentReader 提取文本
    │
    ▼ [Denoise] 自定义 DocumentTransformer 去噪
    │
    ▼ [Chunk] TokenTextSplitter 智能切块
    │
    ▼ [Metadata] 添加 source/docType/chunkIndex 元数据
    │
    ▼ [Load] VectorStore.add() 存入 PgVector（自动向量化）
```

---

## 四、启动步骤

### 4.1 环境准备

| 依赖 | 版本要求 | 说明 |
|------|---------|------|
| JDK | 17+ | Spring Boot 3.x 最低要求 |
| Maven | 3.6+ | 构建工具 |
| PostgreSQL | 15+ | 需安装 pgvector 扩展 |
| 阿里云 DashScope | - | 需申请 API Key |

### 4.2 数据库准备

```sql
-- 1. 安装 pgvector 扩展
CREATE EXTENSION IF NOT EXISTS vector;

-- 2. 创建数据库
CREATE DATABASE smart_cs;
```

### 4.3 修改配置

编辑 `src/main/resources/application.yml`：

```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/smart_cs    # ← 你的数据库地址
    username: postgres                                  # ← 你的用户名
    password: your_password                             # ← 你的密码

  ai:
    dashscope:
      api-key: ${DASHSCOPE_API_KEY:your_api_key_here}  # ← 你的 DashScope API Key
```

### 4.4 启动应用

```bash
# 方式1: Maven 命令行
mvn spring-boot:run

# 方式2: IDE 中直接运行 SmartCsApplication.main()
```

### 4.5 初始化知识库

应用启动后，调用接口加载知识库文档到向量库：

```bash
curl -X POST http://localhost:8080/api/docs/init
```

返回示例：
```json
{"status": "success", "message": "知识库初始化完成", "documentChunks": 42}
```

### 4.6 测试对话

```bash
# 1. 闲聊 (意图识别 → CHAT)
curl -X POST http://localhost:8080/api/chat \
  -H "Content-Type: application/json" \
  -d '{"question": "你好"}'

# 2. RAG 知识库查询 (意图识别 → RAG → Query改写 → 多路召回 → 生成回答)
curl -X POST http://localhost:8080/api/chat \
  -H "Content-Type: application/json" \
  -d '{"question": "怎么退货?"}'

# 3. 数据库查询 (意图识别 → DB_QUERY → NL2SQL → 执行查询 → 解读结果)
curl -X POST http://localhost:8080/api/chat \
  -H "Content-Type: application/json" \
  -d '{"question": "查看所有订单"}'

# 4. 带元数据过滤的 RAG 查询 (只在退货政策文档中检索)
curl "http://localhost:8080/api/chat/filter?question=退货流程&docType=return-policy"

# 5. 流式对话 (SSE 打字机效果)
curl http://localhost:8080/api/chat/stream?question=产品介绍
```

### 4.7 生成文档

```bash
# 生成 Word 订单说明书
curl -O http://localhost:8080/api/docs/download/order-manual

# 生成 PDF 退货说明书（含流程图和图片）
curl -O http://localhost:8080/api/docs/download/return-manual

# 一键生成所有文档
curl -X POST http://localhost:8080/api/docs/generate-all
```

---

## 五、API 接口汇总

| 方法 | 路径 | 功能 |
|------|------|------|
| POST | `/api/chat` | 智能对话（自动意图路由） |
| POST | `/api/chat/filter` | 带文档类型过滤的对话 |
| GET  | `/api/chat/stream` | 流式对话（SSE） |
| POST | `/api/docs/init` | 初始化知识库 |
| POST | `/api/docs/import` | 导入自定义文档 |
| GET  | `/api/docs/search` | 搜索知识库（测试用） |
| GET  | `/api/docs/stats` | 知识库统计 |
| GET  | `/api/docs/download/order-manual` | 下载订单说明书(Word) |
| GET  | `/api/docs/download/return-manual` | 下载退货说明书(PDF) |
| POST | `/api/docs/generate-all` | 一键生成所有文档 |

---

## 六、代码阅读顺序（推荐学习路径）

建议按以下顺序阅读源码，逐步理解 RAG 全链路：

```
第1步: AiConfig.java           → 理解 ChatClient 和 OutputParser 配置
第2步: PromptTemplates.java    → 学习 5 套 Prompt 模板设计
第3步: IntentRecognizer.java   → 理解意图识别 + 结构化输出
第4步: QueryRewriter.java      → 理解 Query 改写策略
第5步: DocumentEtlPipeline.java→ 理解 ETL 全链路（去噪→切块→入库）
第6步: DocumentRetriever.java  → 理解多路召回 + 元数据过滤
第7步: DatabaseQueryService.java→ 理解 NL2SQL + 安全校验
第8步: ChatService.java        → 理解核心编排（串联所有模块）
第9步: DocumentGenerator.java  → 理解 Word/PDF 文档生成
```

---

## 七、进阶优化方向

| 优化点 | 当前实现 | 进阶方案 |
|--------|---------|---------|
| 意图识别 | LLM分类 | 加缓存层（相似查询命中缓存） |
| Query改写 | LLM改写 | HyDE（假设性文档嵌入） |
| 多路召回 | 向量×3路 | 加入BM25关键词检索（混合检索） |
| 切块策略 | TokenTextSplitter | 语义切块（按章节/段落） |
| 重排序 | 无 | 加入 Reranker（Cohere/BGE） |
| NL2SQL | 通用Prompt | 预定义查询模板 + Function Calling |
| 对话记忆 | 无状态 | 加入对话历史（多轮对话上下文） |
| 安全 | SQL黑名单 | SQL Parser 严格分析 + 参数化查询 |
