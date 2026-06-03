# 智能客服系统 - 产品手册

## 1. 产品概述

智能客服系统是一款企业级AI客服解决方案，融合了大语言模型（LLM）和检索增强生成（RAG）技术，
旨在为企业提供智能化、自动化的客户服务能力。

### 核心架构

系统采用三层架构设计：
1. **接入层**: REST API + WebSocket，支持多种客户端接入
2. **AI引擎层**: 意图识别 → 路由分发 → 知识检索/数据查询 → 回答生成
3. **数据层**: PostgreSQL（业务数据）+ PgVector（向量数据）

### 技术栈

- 框架: Spring Boot 3.5 + Spring AI 1.1
- AI引擎: 阿里云DashScope (通义千问)
- 向量数据库: PostgreSQL + pgvector
- 业务数据库: PostgreSQL
- 嵌入模型: text-embedding-v3 (1024维)

## 2. 功能详解

### 2.1 意图识别

意图识别是系统的第一道处理环节，决定用户请求的后续处理路径。

**实现原理**:
- 使用LLM对用户输入进行分类
- 支持三种意图类型: CHAT(闲聊)、RAG(知识库查询)、DB_QUERY(数据库查询)
- 输出结构化结果，包含意图类型、置信度和推理过程

**优化策略**:
- 关键词快速匹配: 简单问候语直接返回，跳过LLM调用
- 缓存机制: 相似查询命中缓存，减少LLM调用次数

### 2.2 RAG 检索增强生成

RAG (Retrieval Augmented Generation) 是本系统的核心能力，通过检索知识库中的相关文档，
为LLM提供上下文信息，从而生成更准确、更可靠的回答。

**完整流程**:

1. **Query改写**: 对用户查询进行多维度改写
   - 语义保持改写: 换表述方式，保持语义不变
   - 关键词扩展: 添加同义词和专业术语
   - 查询分解: 复合问题拆分为子问题

2. **文档去噪**: 清理原始文档中的噪声内容
   - 移除页码、页眉页脚
   - 合并多余空白
   - 过滤无意义片段

3. **文档切块**: 将长文档切分为适当大小的块
   - TokenTextSplitter: 基于token数量的智能切块
   - 参数: chunkSize=800, minChunkSize=350, overlap
   - 保留分隔符: 在段落、句子边界处切分

4. **元数据过滤**: 为每个文档块添加标签
   - source: 文档来源
   - docType: 文档类型 (faq/policy/manual)
   - 支持按标签过滤检索范围

5. **多路召回**: 使用多种策略同时检索
   - 路径1: 原始查询 → 向量检索
   - 路径2: 改写查询 → 向量检索
   - 路径3: 子查询 → 向量检索
   - 合并去重: 按文档ID去重

6. **上下文注入**: 将检索结果注入Prompt，LLM基于真实数据生成回答

### 2.3 NL2SQL 数据库查询

将用户的自然语言问题转换为SQL查询，支持查询以下业务数据：
- **用户表**: 用户信息、角色、状态
- **订单表**: 订单状态、金额、明细
- **权限表**: 权限配置、角色权限

**安全机制**:
- 只允许SELECT查询
- SQL注入检测
- 查询结果行数限制

### 2.4 文档生成

系统支持自动生成多种格式的业务文档：
- **Word文档** (.docx): 订单说明书、产品报告
- **PDF文档** (.pdf): 退货说明书、使用指南（支持嵌入图片）

## 3. API接口

### 对话接口
```
POST /api/chat
Body: { "question": "你的问题" }
Response: { "answer": "AI回答" }
```

### 流式对话
```
GET /api/chat/stream?question=你的问题
返回: SSE (Server-Sent Events) 流式文本
```

### 知识库初始化
```
POST /api/docs/init
Response: { "status": "success", "documentChunks": 100 }
```

### 文档搜索
```
GET /api/docs/search?query=退货&topK=5&docType=return-policy
```

### 文档下载
```
GET /api/docs/download/order-manual   (Word格式订单说明书)
GET /api/docs/download/return-manual  (PDF格式退货说明书)
```

## 4. 部署指南

### 环境要求
- JDK 17+
- PostgreSQL 15+ (需安装pgvector扩展)
- 阿里云DashScope API Key

### 快速开始

1. 安装PostgreSQL并启用pgvector扩展:
   ```sql
   CREATE EXTENSION IF NOT EXISTS vector;
   ```

2. 创建数据库:
   ```sql
   CREATE DATABASE smart_cs;
   ```

3. 配置环境变量:
   ```bash
   export DASHSCOPE_API_KEY=your_api_key
   ```

4. 启动应用:
   ```bash
   mvn spring-boot:run
   ```

5. 初始化知识库:
   ```bash
   curl -X POST http://localhost:8080/api/docs/init
   ```

6. 开始对话:
   ```bash
   curl -X POST http://localhost:8080/api/chat \
     -H "Content-Type: application/json" \
     -d '{"question": "怎么退货?"}'
   ```

## 5. 常见问题

### Q: 向量数据库和业务数据库可以用不同的数据库吗？
A: 可以。本项目共用一个PostgreSQL实例是为了简化部署。生产环境中，建议将向量存储和业务数据分开管理。

### Q: 如何选择嵌入模型？
A: 推荐使用阿里云text-embedding-v3（1024维），支持中英文混合，性价比高。也可以根据需求选择其他模型。

### Q: 如何提高检索准确率？
A: 可以从以下几个方面优化：
1. 文档质量: 确保知识库文档内容清晰、结构完整
2. 切块策略: 调整chunkSize找到最佳平衡点
3. Query改写: 启用多路召回提升召回率
4. 元数据过滤: 使用文档类型标签缩小搜索范围
5. 相似度阈值: 调整similarityThreshold过滤低质量结果
