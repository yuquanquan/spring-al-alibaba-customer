# 分布式记忆架构 - Redis ChatMemory 实现

## 🎯 问题解决

### 单机环境的局限性

```java
// MessageWindowChatMemory 底层是 ConcurrentHashMap（内存）
private final Map<String, Deque<Message>> sessions = new ConcurrentHashMap<>();
```

**问题场景：**
```
用户请求 → 负载均衡器
  ├─→ 实例A：保存消息到 A的内存
  ├─→ 用户下次请求 → 负载均衡器
  └─→ 实例B：查不到之前的消息！（数据在实例A的内存中）
```

---

## ✅ 解决方案：Redis-backed ChatMemory

### 架构图

```
┌─────────────────────────────────────────────────────┐
│              用户请求层（多实例）                      │
│  Instance A    Instance B    Instance C              │
└───────┬────────────┬────────────┬────────────────────┘
        │            │            │
        └────────────┼────────────┘
                     │
                     ▼
        ┌────────────────────────┐
        │   Redis Cluster        │
        │   chat-memory:{sid}    │  ← 所有实例共享
        └────────────────────────┘
```

---

## 📦 实现步骤

### 1. 添加依赖（pom.xml）

```xml
<!-- Spring Data Redis -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-data-redis</artifactId>
</dependency>

<!-- Spring AI Redis ChatMemory -->
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-chat-memory-redis</artifactId>
</dependency>
```

### 2. 配置 Redis（application.yml）

```yaml
spring:
  data:
    redis:
      host: ${REDIS_HOST:localhost}
      port: ${REDIS_PORT:6379}
      password: ${REDIS_PASSWORD:}
      database: 0
      timeout: 3000ms
      lettuce:
        pool:
          max-active: 8
          max-idle: 8
          min-idle: 2
          max-wait: -1ms

app:
  chat-memory:
    window-size: 10          # 滑动窗口大小
    ttl-seconds: 86400       # 24小时过期
```

### 3. 修改配置类（ChatMemoryConfig.java）

```java
@Configuration
public class ChatMemoryConfig {

    @Value("${app.chat-memory.window-size:10}")
    private int windowSize;

    @Value("${app.chat-memory.ttl-seconds:86400}")
    private int ttlSeconds;

    @Bean
    public ChatMemory shortTermMemory(RedisConnectionFactory redisConnectionFactory) {
        return RedisChatMemory.builder()
            .connectionFactory(redisConnectionFactory)
            .maxMessages(windowSize)
            .ttl(java.time.Duration.ofSeconds(ttlSeconds))
            .build();
    }
}
```

---

## 🔍 Redis 存储结构

### Key-Value 设计

```
Key: chat-memory:user-001

Value (Hash):
{
  "1": "{\"role\":\"USER\",\"content\":\"我叫张三\"}",
  "2": "{\"role\":\"ASSISTANT\",\"content\":\"你好，张三！\"}",
  "3": "{\"role\":\"USER\",\"content\":\"我的爱好是什么？\"}",
  ...
  "10": "{\"role\":\"ASSISTANT\",\"content\":\"你的爱好是打篮球。\"}"
}

TTL: 86400秒（24小时后自动删除）
```

### 滑动窗口机制

```
第11条消息到来时：
1. 删除 messageIndex=1 的记录
2. 插入 messageIndex=11 的记录
3. 保持总数量 ≤ 10
```

---

## 📊 性能对比

| 指标 | 内存版 | Redis版 |
|------|--------|---------|
| **读取延迟** | < 0.1ms | ~1ms |
| **写入延迟** | < 0.1ms | ~1ms |
| **分布式支持** | ❌ 不支持 | ✅ 完全支持 |
| **持久化** | ❌ 重启丢失 | ✅ RDB/AOF |
| **内存占用** | JVM Heap | Redis Server |
| **适用场景** | 单机开发 | 生产环境 |

---

## 🧪 测试验证

### 启动 Redis

```bash
# Docker 方式
docker run -d --name redis -p 6379:6379 redis:7-alpine

# 或使用本地安装的 Redis
redis-server
```

### 测试多实例共享

```bash
# 实例1：保存消息
curl -X POST http://localhost:8081/api/chat \
  -H "Content-Type: application/json" \
  -d '{"question": "我叫张三", "sessionId": "test-001"}'

# 实例2：查询历史（应该能记住）
curl -X POST http://localhost:8082/api/chat \
  -H "Content-Type: application/json" \
  -d '{"question": "我叫什么名字？", "sessionId": "test-001"}'

# 预期响应：你叫张三
```

### 查看 Redis 数据

```bash
# 连接 Redis
redis-cli

# 查看所有会话
KEYS chat-memory:*

# 查看某个会话的消息
HGETALL chat-memory:test-001

# 查看 TTL
TTL chat-memory:test-001
```

---

## 🚀 生产环境优化

### 1. Redis Cluster（高可用）

```yaml
spring:
  data:
    redis:
      cluster:
        nodes:
          - redis-node1:6379
          - redis-node2:6379
          - redis-node3:6379
        max-redirects: 3
```

### 2. 连接池调优

```yaml
spring:
  data:
    redis:
      lettuce:
        pool:
          max-active: 50      # 增加最大连接数
          max-idle: 20        # 增加空闲连接
          min-idle: 5         # 最小空闲连接
          max-wait: 5000ms    # 等待超时
```

### 3. 序列化优化

```java
@Configuration
public class RedisConfig {
    
    @Bean
    public RedisSerializer<Object> redisSerializer() {
        // 使用 JSON 序列化（可读性好）
        return new GenericJackson2JsonRedisSerializer();
        
        // 或使用 Protobuf（性能更好）
        // return new ProtobufRedisSerializer();
    }
}
```

---

## ⚠️ 注意事项

### 1. 网络延迟

```
应用服务器 → Redis（同一机房）: ~1ms
应用服务器 → Redis（跨机房）: ~10-50ms
```

**建议：** Redis 和应用服务器部署在同一机房或同一 VPC。

### 2. 内存管理

```bash
# 监控 Redis 内存使用
redis-cli INFO memory

# 设置最大内存
CONFIG SET maxmemory 2gb
CONFIG SET maxmemory-policy allkeys-lru
```

### 3. 故障降级

```java
@Service
public class ChatService {
    
    @Autowired(required = false)
    private ChatMemory redisMemory;  // 可能为 null
    
    private ChatMemory fallbackMemory = MessageWindowChatMemory.builder().build();
    
    public String chat(String sessionId, String question) {
        ChatMemory memory = redisMemory != null ? redisMemory : fallbackMemory;
        memory.add(sessionId, new UserMessage(question));
        // ...
    }
}
```

---

## 🎯 总结

### 核心优势

1. ✅ **分布式共享**：多实例无缝协作
2. ✅ **高性能**：~1ms 延迟，满足实时性要求
3. ✅ **持久化**：RDB/AOF 保证数据不丢失
4. ✅ **自动过期**：TTL 防止内存泄漏
5. ✅ **官方支持**：Spring AI 原生集成，无需自定义

### 适用场景

| 场景 | 推荐方案 |
|------|---------|
| 单机开发/测试 | MessageWindowChatMemory（内存） |
| 小规模生产（< 1000 QPS） | Redis Standalone |
| 中等规模（1000-10000 QPS） | Redis Sentinel |
| 大规模（> 10000 QPS） | Redis Cluster |

---

## 📚 参考资料

- [Spring AI Redis ChatMemory 官方文档](https://docs.spring.io/spring-ai/reference/api/chat/memory.html)
- [Redis 最佳实践](https://redis.io/docs/manual/)
- [Spring Data Redis 配置指南](https://docs.spring.io/spring-data/redis/reference/)
