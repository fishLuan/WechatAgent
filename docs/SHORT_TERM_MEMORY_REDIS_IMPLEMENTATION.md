# Redis 短期记忆实施教程

## 1. 目标

当前项目的普通聊天调用链是：

`WechatMessagePollingService -> WechatMessageServiceImpl -> QwenAiChatService`

`QwenAiChatService` 每次请求只发送：

- `system`：系统提示词
- `user`：当前用户消息

因此机器人没有短期记忆。用户上一句说过的信息，下一句不会被模型看到。

本方案用 Redis 实现“简短上下文工程”：按微信用户保存最近几轮对话，在调用千问文本模型前取出历史消息，一起拼进 `messages`，让模型具备短期上下文。

## 2. 技术选型

选择 Redis，原因：

- 读写快，适合聊天上下文这种高频短数据。
- 支持 TTL，过期自动清理，避免无限增长。
- 支持 List，天然适合保存最近 N 条消息。
- 后续可以平滑升级到摘要记忆、用户画像、长期记忆。

建议短期记忆只保存最近 `6-10` 轮对话，避免 token 太长。

## 3. 目标架构

新增结构建议：

```text
WechatAI
├─ config
│  ├─ AiProperties.java
│  ├─ AppConfigLoader.java
│  └─ RedisClientFactory.java
├─ model
│  ├─ ChatMessage.java
│  └─ MemoryRecord.java
├─ service
│  ├─ AiChatService.java
│  ├─ MemoryService.java
│  └─ impl
│     ├─ QwenAiChatService.java
│     ├─ RedisMemoryService.java
│     └─ WechatMessageServiceImpl.java
```

核心改造思路：

- `WechatMessageServiceImpl` 负责拿到 `fromUserId`。
- `MemoryService` 负责读写 Redis。
- `QwenAiChatService` 支持传入历史消息。
- 每次聊天前读取短期记忆，聊天后写入用户消息和助手回复。

## 4. Redis 数据设计

Key 设计：

```text
wechat:memory:{fromUserId}
```

Value 使用 Redis List，每个元素是一条 JSON：

```json
{
  "role": "user",
  "content": "我叫小明",
  "timestamp": 1784520000000
}
```

角色只需要两种：

- `user`
- `assistant`

保存策略：

- 每次对话写入两条：用户消息、机器人回复。
- 每个用户最多保留 `20` 条消息，也就是约 `10` 轮对话。
- 每次写入后刷新 TTL，例如 `30` 分钟。

Redis 操作：

```text
RPUSH wechat:memory:{fromUserId} messageJson
LTRIM wechat:memory:{fromUserId} -20 -1
EXPIRE wechat:memory:{fromUserId} 1800
LRANGE wechat:memory:{fromUserId} 0 -1
```

## 5. 配置项

在 `src/main/resources/config.properties` 增加：

```properties
redis.host=127.0.0.1
redis.port=6379
redis.password=${REDIS_PASSWORD}
redis.database=0

memory.enabled=true
memory.max.messages=20
memory.ttl.seconds=1800
```

在 `config.properties.example` 同步增加相同配置，不要写真实密码。

如果本地 Redis 没有密码，可以让 `REDIS_PASSWORD` 为空，代码中按空密码处理。

## 6. Maven 依赖

推荐使用 Jedis，当前项目不是标准 Spring Bean 管理，Jedis 接入更直接。

在 `pom.xml` 增加：

```xml
<dependency>
    <groupId>redis.clients</groupId>
    <artifactId>jedis</artifactId>
    <version>5.2.0</version>
</dependency>
```

## 7. Model 层设计

新增 `ChatMessage`：

```java
package WechatAI.model;

public class ChatMessage {
    private String role;
    private String content;

    public ChatMessage(String role, String content) {
        this.role = role;
        this.content = content;
    }

    public String getRole() {
        return role;
    }

    public String getContent() {
        return content;
    }
}
```

新增 `MemoryRecord`：

```java
package WechatAI.model;

public class MemoryRecord {
    private String role;
    private String content;
    private long timestamp;

    public MemoryRecord(String role, String content, long timestamp) {
        this.role = role;
        this.content = content;
        this.timestamp = timestamp;
    }

    public String getRole() {
        return role;
    }

    public String getContent() {
        return content;
    }

    public long getTimestamp() {
        return timestamp;
    }
}
```

## 8. Service 层设计

新增接口 `MemoryService`：

```java
package WechatAI.service;

import WechatAI.model.ChatMessage;
import java.util.List;

public interface MemoryService {
    List<ChatMessage> loadRecentMessages(String userId);

    void appendConversation(String userId, String userMessage, String assistantMessage);

    void clear(String userId);
}
```

新增实现 `RedisMemoryService`：

- `loadRecentMessages`：从 Redis 读取 List 并解析 JSON。
- `appendConversation`：写入 user 和 assistant 两条记录。
- `clear`：删除指定用户上下文。

注意：Redis 异常不要影响主聊天流程。Redis 挂了时，只打印日志，然后退化成无记忆聊天。

## 9. AiChatService 改造

当前接口：

```java
String chat(String userMessage);
```

建议改成：

```java
String chat(String userMessage, List<ChatMessage> historyMessages);
```

`QwenAiChatService` 组装 `messages` 的顺序：

```text
1. system prompt
2. Redis 历史消息
3. 当前 user message
```

这样模型能看到最近上下文。

示例：

```json
[
  {"role": "system", "content": "你是一个友好的微信AI助手..."},
  {"role": "user", "content": "我叫小明"},
  {"role": "assistant", "content": "好的，小明，很高兴认识你。"},
  {"role": "user", "content": "我叫什么？"}
]
```

## 10. WechatMessageServiceImpl 改造

普通文本聊天流程改成：

```java
List<ChatMessage> history = memoryService.loadRecentMessages(fromUserId);
String replyText = aiChatService.chat(receivedText, history);
memoryService.appendConversation(fromUserId, receivedText, replyText);
```

语音识别后的普通聊天也应使用同一套记忆：

```java
List<ChatMessage> history = memoryService.loadRecentMessages(fromUserId);
String replyText = aiChatService.chat(recognizedText, history);
memoryService.appendConversation(fromUserId, recognizedText, replyText);
```

图片生成、语音生成这类工具型指令默认不写入记忆，避免污染上下文。

图片理解可以按产品需求决定：

- 如果希望机器人记住图片内容，可以写入摘要。
- 如果只是临时看图，不建议写入。

## 11. 清除记忆指令

建议支持用户发送：

```text
清空记忆
忘记上下文
重置对话
```

处理逻辑：

```java
if (isClearMemoryRequest(receivedText)) {
    memoryService.clear(fromUserId);
    client.sendTextWithTyping(fromUserId, "好的，我已经清空这段对话记忆了。", TYPING_DELAY_MS);
    return;
}
```

## 12. 并发注意事项

你当前项目已经把消息处理改成多线程。Redis 短期记忆接入后，需要注意同一个用户并发发送多条消息时的顺序问题。

短期可接受方案：

- Redis 使用 `RPUSH + LTRIM + EXPIRE`，单条命令是原子的。
- 偶发顺序错乱影响不大。

更稳方案：

- 同一个 `fromUserId` 的消息进入同一个单线程队列。
- 不同用户之间并发处理。

推荐后续升级为：

```text
UserSerialMessageDispatcher
├─ userA -> single thread queue
├─ userB -> single thread queue
└─ userC -> single thread queue
```

这样既能并发，又能保证单用户上下文顺序。

## 13. 最小实施步骤

1. 增加 Redis 配置项。
2. 增加 Jedis 依赖。
3. 新增 `ChatMessage`、`MemoryRecord`。
4. 新增 `MemoryService` 接口。
5. 新增 `RedisMemoryService` 实现。
6. 改造 `AiChatService`，支持历史消息参数。
7. 改造 `QwenAiChatService`，把历史消息拼入 `messages`。
8. 改造 `WechatMessageServiceImpl`，普通聊天前读记忆，聊天后写记忆。
9. 增加“清空记忆”指令。
10. 本地启动 Redis 并测试。

## 14. 本地测试方式

启动 Redis：

```powershell
docker run --name wechat-bot-redis -p 6379:6379 -d redis:7
```

测试对话：

```text
用户：我叫阿康
机器人：好的，阿康。

用户：我叫什么？
机器人：你叫阿康。
```

查看 Redis：

```powershell
docker exec -it wechat-bot-redis redis-cli
LRANGE wechat:memory:o9cq805jSdvggonNUKqO22kxa9xg@im.wechat 0 -1
TTL wechat:memory:o9cq805jSdvggonNUKqO22kxa9xg@im.wechat
```

## 15. 验收标准

功能验收：

- 用户说“我叫某某”，下一句问“我叫什么”，机器人能答出。
- 30 分钟不互动后，短期记忆自动消失。
- 用户发送“清空记忆”，Redis 中对应 key 被删除。
- Redis 停止时，机器人仍能正常聊天，只是不带记忆。

工程验收：

- API Key 和 Redis 密码都通过环境变量占位符配置。
- 记忆逻辑在 `MemoryService` 中，不写死在 `QwenAiChatService`。
- `QwenAiChatService` 只负责模型请求，不直接操作 Redis。
- 多线程下不会因为 Redis 异常导致消息处理线程崩掉。

## 16. 后续升级方向

短期记忆稳定后，可以继续升级：

- 摘要记忆：超过 N 轮后，把旧对话压缩成一段摘要。
- 用户画像：保存用户偏好，例如称呼、语言风格、常用需求。
- 长期记忆：把重要事实单独存储，和短期上下文分离。
- 向量检索：用向量数据库召回历史片段，适合更长周期记忆。
