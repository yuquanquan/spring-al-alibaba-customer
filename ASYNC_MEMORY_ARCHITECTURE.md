# 事件驱动异步记忆架构 - 实现说明

## 🎯 优化目标

### 优化前（同步阻塞）

```
用户请求 → chat() 
  ├─→ saveUserMessage()     ← 阻塞 ~10ms（写数据库）
  ├─→ 意图识别              ← 阻塞 ~500ms（LLM调用）
  ├─→ RAG检索               ← 阻塞 ~200ms（向量查询）
  ├─→ LLM生成回答           ← 阻塞 ~2000ms（LLM推理）
  ├─→ saveAssistantMessage() ← 阻塞 ~10ms（写数据库）
  └─→ compressIfNeeded()    ← 阻塞 ~3000ms（如果需要压缩，调用LLM总结）
  
总延迟：~5720ms（用户体验差！）
```

### 优化后（事件驱动异步）

```
用户请求 → chat() 
  ├─→ shortTermMemory.add() ← < 1ms（内存操作）
  ├─→ publishEvent()        ← < 1ms（发布事件，非阻塞）
  ├─→ 意图识别              ← ~500ms（LLM调用）
  ├─→ RAG检索               ← ~200ms（向量查询）
  ├─→ LLM生成回答           ← ~2000ms（LLM推理）
  ├─→ shortTermMemory.add() ← < 1ms（内存操作）
  └─→ publishEvent()        ← < 1ms（发布事件，非阻塞）
  
主流程总延迟：~2702ms（提升 52.8%！）

后台异步线程：
  └─→ MemoryPersistListener.onMemoryPersist()
      ├─→ saveUserMessage()      ← ~10ms（数据库写入）
      ├─→ saveAssistantMessage() ← ~10ms（数据库写入）
      └─→ compressIfNeeded()     ← ~3000ms（记忆压缩，如果需要）
```

---

## 📦 核心组件

### 1. MemoryPersistEvent（记忆事件）

**位置：** `com.example.smartcs.event.MemoryPersistEvent`

**作用：** 封装记忆持久化所需的数据

```java
public class MemoryPersistEvent extends ApplicationEvent {
    private final String sessionId;   // 会话ID
    private final String role;        // USER / ASSISTANT
    private final String content;     // 消息内容
    private final LocalDateTime timestamp;  // 时间戳
}
```

---

### 2. AsyncConfig（异步配置）

**位置：** `com.example.smartcs.config.AsyncConfig`

**作用：** 为记忆持久化提供专用线程池

```java
@Configuration
@EnableAsync
public class AsyncConfig {
    
    @Bean("memoryExecutor")
    public Executor memoryExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);        // 核心线程数
        executor.setMaxPoolSize(5);         // 最大线程数
        executor.setQueueCapacity(1000);    // 队列容量
        executor.setThreadNamePrefix("memory-async-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        return executor;
    }
}
```

**线程池参数说明：**
- **corePoolSize=2**：常驻2个线程处理记忆任务
- **maxPoolSize=5**：高峰期最多5个线程
- **queueCapacity=1000**：最多缓存1000个待处理任务
- **CallerRunsPolicy**：队列满时由调用线程执行（保证不丢失）

---

### 3. MemoryPersistListener（异步监听器）

**位置：** `com.example.smartcs.listener.MemoryPersistListener`

**作用：** 异步处理记忆持久化

```java
@Slf4j
@Component
public class MemoryPersistListener {
    
    @Autowired
    private ChatMemoryService memoryService;
    
    @Async("memoryExecutor")  // ← 使用专用线程池
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)  // ← 事务提交后触发
    public void onMemoryPersist(MemoryPersistEvent event) {
        try {
            // 步骤1: 保存到长期记忆（PostgreSQL）
            if ("USER".equals(event.getRole())) {
                memoryService.saveUserMessage(event.getSessionId(), event.getContent());
            } else {
                memoryService.saveAssistantMessage(event.getSessionId(), event.getContent());
            }
            
            // 步骤2: 检查是否需要压缩记忆
            memoryService.compressIfNeeded(event.getSessionId());
            
        } catch (Exception e) {
            // ⚠️ 关键：不能抛出异常，否则会影响主流程
            log.error("【异步记忆】失败", e);
        }
    }
}
```

**关键注解：**
- `@Async("memoryExecutor")`：异步执行，使用专用线程池
- `@TransactionalEventListener(phase = AFTER_COMMIT)`：事务提交后才触发

---

### 4. ChatService（改造后）

**位置：** `com.example.smartcs.service.ChatService`

**变化：**
- ❌ 移除：`ChatMemoryService` 直接调用
- ✅ 新增：`ApplicationEventPublisher` 事件发布器
- ✅ 保留：`MessageWindowChatMemory` 短期记忆（同步）

```java
public String chat(String sessionId, String question) {
    // ===== 步骤0: 保存用户消息到短期记忆（同步） =====
    shortTermMemory.add(sessionId, new UserMessage(question));
    
    // ===== 步骤0.5: 发布长期记忆事件（异步，非阻塞） =====
    eventPublisher.publishEvent(new MemoryPersistEvent(this, sessionId, "USER", question));
    
    // ... 意图识别、RAG、LLM生成 ...
    String answer = generateAnswer(question);
    
    // ===== 步骤3: 保存AI回复到短期记忆（同步） =====
    shortTermMemory.add(sessionId, new AssistantMessage(answer));
    
    // ===== 步骤3.5: 发布长期记忆事件（异步，非阻塞） =====
    eventPublisher.publishEvent(new MemoryPersistEvent(this, sessionId, "ASSISTANT", answer));
    
    return answer;  // 立即返回，无需等待数据库写入
}
```

---

```

---

## 🔄 执行流程

### 完整时序图

```
用户请求
  │
  ▼
┌─────────────────────────────────────┐
│ ChatService.chat()                  │
│                                     │
│ 1. shortTermMemory.add()            │ ← 同步，< 1ms
│    (内存滑动窗口)                     │
│                                     │
│ 2. eventPublisher.publishEvent()    │ ← 同步，< 1ms
│    (发布 MemoryPersistEvent)         │
│                                     │
│ 3. 意图识别                         │ ← ~500ms
│                                     │
│ 4. RAG检索                          │ ← ~200ms
│                                     │
│ 5. LLM生成回答                      │ ← ~2000ms
│                                     │
│ 6. shortTermMemory.add()            │ ← 同步，< 1ms
│                                     │
│ 7. eventPublisher.publishEvent()    │ ← 同步，< 1ms
│                                     │
│ 8. return answer                    │ ← 立即返回！
└──────────────┬──────────────────────┘
               │
               │ 事务提交后触发
               ▼
┌─────────────────────────────────────┐
│ MemoryPersistListener               │
│ (异步线程池: memoryExecutor)         │
│                                     │
│ 1. saveUserMessage()                │ ← ~10ms
│    (PostgreSQL)                      │
│                                     │
│ 2. saveAssistantMessage()           │ ← ~10ms
│    (PostgreSQL)                      │
│                                     │
│ 3. compressIfNeeded()               │ ← ~3000ms (如果需要)
│    (调用LLM总结对话)                  │
└─────────────────────────────────────┘
```

---

## 📊 性能对比

| 指标 | 优化前（同步） | 优化后（异步） | 提升 |
|------|--------------|--------------|------|
| **主流程延迟** | ~5720ms | ~2702ms | **52.8%** ⬆️ |
| **数据库写入** | 阻塞主流程 | 后台异步 | 不阻塞 ✅ |
| **记忆压缩** | 阻塞主流程 | 后台异步 | 不阻塞 ✅ |
| **故障隔离** | ❌ 写入失败影响对话 | ✅ 写入失败不影响 | 可靠性 ⬆️ |
| **可扩展性** | ❌ 难以添加新功能 | ✅ 轻松添加监听器 | 可维护性 ⬆️ |

---

## 🧪 测试验证

### 1. 启动项目

```bash
mvn spring-boot:run
```

### 2. 观察日志

**主流程日志：**
```
【智能客服】会话: test-001, 用户问题: 我叫张三
【意图识别】→ CHAT
【闲聊路由】直接回复
```

**异步日志（稍后出现）：**
```
【异步记忆】开始处理: sessionId=test-001, role=USER
【异步记忆】完成: sessionId=test-001, duration=15ms
【异步记忆】开始处理: sessionId=test-001, role=ASSISTANT
【异步记忆】完成: sessionId=test-001, duration=12ms
```

### 3. 验证记忆功能

```bash
# 第一轮：告诉 AI 你的名字
curl -X POST http://localhost:8080/api/chat \
  -H "Content-Type: application/json" \
  -d '{"question": "我叫张三", "sessionId": "test-001"}'

# 第二轮：询问 AI 是否记得
curl -X POST http://localhost:8080/api/chat \
  -H "Content-Type: application/json" \
  -d '{"question": "我叫什么名字？", "sessionId": "test-001"}'

# 预期响应：你叫张三
```

### 4. 查看数据库记录

```sql
-- 查看某个会话的完整历史
SELECT * FROM chat_history 
WHERE session_id = 'test-001' 
ORDER BY message_index ASC;
```

---

## ⚠️ 注意事项

### 1. 事务一致性

```java
@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
```

- ✅ **事务提交后才触发**：保证数据一致性
- ✅ **事务回滚不触发**：避免脏数据持久化

### 2. 异常处理

```java
try {
    // 记忆持久化逻辑
} catch (Exception e) {
    log.error("【异步记忆】失败", e);
    // ⚠️ 不能抛出异常，否则会影响主流程
}
```

### 3. 线程池调优

根据实际负载调整线程池参数：

```yaml
# 低并发（< 100 QPS）
corePoolSize: 2
maxPoolSize: 5
queueCapacity: 1000

# 中并发（100-1000 QPS）
corePoolSize: 5
maxPoolSize: 10
queueCapacity: 5000

# 高并发（> 1000 QPS）
corePoolSize: 10
maxPoolSize: 20
queueCapacity: 10000
```

### 4. 监控告警

建议添加监控指标：

```java
// TODO: 集成 Prometheus + Grafana
// - 异步任务执行时间
// - 队列积压数量
// - 失败率统计
```

---

## 🚀 未来扩展

### 1. 添加用户画像监听器

```java
@Component
public class UserProfileListener {
    
    @Async("memoryExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onUpdateUserProfile(MemoryPersistEvent event) {
        // 从对话中提取用户偏好
        UserProfile profile = extractProfile(event.getContent());
        userProfileRepo.update(event.getSessionId(), profile);
    }
}
```

### 2. 添加数据分析监听器

```java
@Component
public class AnalyticsListener {
    
    @Async("analyticsExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onTrackUserBehavior(MemoryPersistEvent event) {
        // 记录用户行为到数据仓库
        analyticsService.track(event.getSessionId(), event.getContent());
    }
}
```

### 3. 添加实时通知监听器

```java
@Component
public class NotificationListener {
    
    @Async("notificationExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onNotifyAdmin(MemoryPersistEvent event) {
        // 检测到敏感词时通知管理员
        if (containsSensitiveWord(event.getContent())) {
            notificationService.sendAlert(event);
        }
    }
}
```

---

## 🎯 总结

### 核心优势

1. ✅ **性能提升 52.8%**：主流程从 5720ms → 2702ms
2. ✅ **故障隔离**：记忆写入失败不影响对话功能
3. ✅ **可扩展性强**：轻松添加新监听器（用户画像、数据分析等）
4. ✅ **事务一致性**：AFTER_COMMIT 保证数据一致性
5. ✅ **实现简单**：Spring Event + @Async，无需引入消息队列

### 适用场景

| 场景 | 推荐方案 |
|------|---------|
| 单机开发/测试 | ✅ 当前方案（内存 + 异步） |
| 小规模生产（< 1000 QPS） | ✅ 当前方案 |
| 中等规模（1000-10000 QPS） | ⚠️ 考虑引入 Kafka |
| 大规模（> 10000 QPS） | ❌ 必须用消息队列 |

### 下一步优化

1. **添加监控**：Prometheus + Grafana 监控异步任务
2. **重试机制**：失败任务自动重试（最多3次）
3. **死信队列**：多次失败后存入死信队列，人工处理
4. **批量写入**：积累100条消息后批量插入数据库

---

## 📚 参考资料

- [Spring Event 官方文档](https://docs.spring.io/spring-framework/reference/core/beans/context-introduction.html#context-functionality-events)
- [Spring @Async 最佳实践](https://spring.io/guides/gs/async-method/)
- [线程池调优指南](https://www.baeldung.com/thread-pool-java-and-guava)
