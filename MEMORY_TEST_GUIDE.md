# 多轮对话记忆功能测试指南

## 📋 功能概述

本系统已实现完整的**两级记忆架构**：

1. **短期记忆**：内存中保留最近10条消息（快速访问）
2. **长期记忆**：PostgreSQL持久化存储（跨会话恢复）
3. **记忆压缩**：超过20条消息时自动调用LLM总结对话

---

## 🧪 测试步骤

### 1. 启动项目

```bash
mvn spring-boot:run
```

### 2. 测试多轮对话（带 sessionId）

#### 第一轮对话
```bash
curl -X POST http://localhost:8080/api/chat \
  -H "Content-Type: application/json" \
  -d '{
    "question": "你好，我是张三",
    "sessionId": "user-zhangsan-001"
  }'
```

**预期响应：**
```json
{
  "answer": "你好，张三！很高兴认识你。有什么我可以帮助你的吗？"
}
```

#### 第二轮对话（测试记忆）
```bash
curl -X POST http://localhost:8080/api/chat \
  -H "Content-Type: application/json" \
  -d '{
    "question": "我叫什么名字？",
    "sessionId": "user-zhangsan-001"
  }'
```

**预期响应：**
```json
{
  "answer": "你叫张三。"
}
```

✅ **如果AI能正确回答"张三"，说明记忆功能正常工作！**

---

### 3. 查看数据库中的记忆记录

```sql
-- 查看某个会话的完整历史
SELECT * FROM chat_history 
WHERE session_id = 'user-zhangsan-001' 
ORDER BY message_index ASC;

-- 查看记忆统计
SELECT 
    session_id,
    COUNT(*) as message_count,
    SUM(token_count) as total_tokens,
    MAX(message_index) as last_index
FROM chat_history
GROUP BY session_id;
```

---

### 4. 测试不同会话的记忆隔离

#### 会话A
```bash
curl -X POST http://localhost:8080/api/chat \
  -H "Content-Type: application/json" \
  -d '{
    "question": "我的爱好是打篮球",
    "sessionId": "session-A"
  }'
```

#### 会话B
```bash
curl -X POST http://localhost:8080/api/chat \
  -H "Content-Type: application/json" \
  -d '{
    "question": "我的爱好是游泳",
    "sessionId": "session-B"
  }'
```

#### 验证会话A的记忆
```bash
curl -X POST http://localhost:8080/api/chat \
  -H "Content-Type: application/json" \
  -d '{
    "question": "我的爱好是什么？",
    "sessionId": "session-A"
  }'
```

**预期响应：** "你的爱好是打篮球。"

#### 验证会话B的记忆
```bash
curl -X POST http://localhost:8080/api/chat \
  -H "Content-Type: application/json" \
  -d '{
    "question": "我的爱好是什么？",
    "sessionId": "session-B"
  }'
```

**预期响应：** "你的爱好是游泳。"

✅ **两个会话的记忆应该完全隔离，互不干扰！**

---

### 5. 测试流式接口（带记忆）

```bash
# 使用浏览器访问
http://localhost:8080/api/chat/stream?question=我叫李四&sessionId=user-lisi-002

# 或使用 curl（SSE 格式输出）
curl -N "http://localhost:8080/api/chat/stream?question=我叫李四&sessionId=user-lisi-002"
```

---

### 6. 测试记忆压缩（需要20+条消息）

运行以下脚本触发记忆压缩：

```bash
#!/bin/bash
SESSION_ID="test-compression-001"

for i in {1..25}; do
  echo "发送第 $i 条消息..."
  curl -s -X POST http://localhost:8080/api/chat \
    -H "Content-Type: application/json" \
    -d "{
      \"question\": \"这是第 $i 条测试消息\",
      \"sessionId\": \"$SESSION_ID\"
    }" > /dev/null
done

echo "完成！检查数据库中的压缩记录："
psql -U postgres -d smartcs -c "
SELECT 
    role,
    content,
    compressed,
    message_index
FROM chat_history 
WHERE session_id = '$SESSION_ID' 
ORDER BY message_index ASC;"
```

**预期结果：**
- 前20条消息标记为 `compressed=true`
- 新增1条 SYSTEM 角色的总结消息
- 总结消息内容类似："【对话总结】用户进行了25轮测试对话..."

---

## 🔍 调试技巧

### 1. 查看日志中的记忆操作

```bash
# 启动项目时开启 DEBUG 级别日志
mvn spring-boot:run -Dlogging.level.com.example.smartcs.service.ChatMemoryService=DEBUG
```

**关键日志：**
```
【长期记忆】保存用户消息: sessionId=user-zhangsan-001, index=1
【长期记忆】保存AI回复: sessionId=user-zhangsan-001, index=2
【短期记忆】加载10条历史消息
【记忆压缩】触发压缩: sessionId=test-001, 消息数=20
【记忆压缩】完成: 原20条消息 → 1条总结
```

### 2. 手动清理会话记忆

```bash
curl -X DELETE "http://localhost:8080/api/chat/memory?sessionId=user-zhangsan-001"
```

或在数据库中删除：

```sql
DELETE FROM chat_history WHERE session_id = 'user-zhangsan-001';
```

---

## 📊 性能指标

| 指标 | 目标值 | 说明 |
|------|--------|------|
| 短期记忆加载延迟 | < 10ms | 从数据库查询最近10条消息 |
| 长期记忆写入延迟 | < 20ms | 插入单条消息到PostgreSQL |
| 记忆压缩触发阈值 | 20条消息 | 可配置（COMPRESSION_THRESHOLD） |
| 压缩后Token节省率 | ~95% | 从5000 tokens → 200 tokens |
| 会话记忆隔离 | ✅ 完全隔离 | 通过 sessionId 区分 |

---

## ❓ 常见问题

### Q1: 为什么AI记不住我之前说的话？

**可能原因：**
1. 没有传递 `sessionId` 参数（每次都是新会话）
2. 数据库连接失败（检查 PostgreSQL 是否运行）
3. `chat_history` 表未创建（运行 schema.sql）

**解决方案：**
```bash
# 确保每次请求都携带相同的 sessionId
curl -X POST http://localhost:8080/api/chat \
  -H "Content-Type: application/json" \
  -d '{"question": "...", "sessionId": "固定ID"}'
```

### Q2: 如何调整短期记忆的消息数量？

修改 `ChatMemoryService.java`：

```java
private static final int SHORT_TERM_WINDOW = 10;  // 改为其他值
```

### Q3: 如何禁用记忆压缩？

注释掉 `ChatService.java` 中的压缩调用：

```java
// chatMemoryService.compressIfNeeded(sessionId);
```

---

## 🎯 下一步优化方向

1. **向量记忆检索**：将长期记忆向量化，支持语义搜索历史对话
2. **用户画像提取**：从对话历史中自动提取用户偏好、习惯等信息
3. **主动回忆机制**：当用户提到"上次说过..."时，主动检索相关历史
4. **记忆优先级**：重要信息（如用户名）永久保留，临时信息定期清理
