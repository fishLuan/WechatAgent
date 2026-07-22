# 2026-07-20 功能实施与问题复盘

## 1. 今日目标

今天主要围绕微信 AI Bot 的多模态能力、语音能力、异步处理和短期记忆进行开发与修复。

最终目标是让项目具备：

- 千问统一模型配置。
- 图片理解与图片生成能力。
- 语音识别能力。
- 文字转语音并发送音频文件能力。
- 模型调用期间显示“对方正在输入中”。
- 多线程异步处理消息，避免慢任务阻塞聊天。
- Redis 短期记忆，让普通聊天具备简短上下文。

## 2. 当前项目架构

项目已按接近 SpringBoot 的分层风格整理：

```text
WechatAI
├─ config
│  ├─ AiProperties.java
│  ├─ AppConfigLoader.java
│  └─ WechatClientFactory.java
├─ model
│  ├─ ChatMessage.java
│  ├─ MemoryRecord.java
│  ├─ MediaType.java
│  └─ VoiceReply.java
├─ service
│  ├─ AiChatService.java
│  ├─ ImageGenerationService.java
│  ├─ ImageUnderstandingService.java
│  ├─ MemoryService.java
│  ├─ MessagePollingService.java
│  ├─ MessageTextExtractor.java
│  ├─ SpeechRecognitionService.java
│  ├─ SpeechSynthesisService.java
│  └─ WechatMessageService.java
└─ service.impl
   ├─ QwenAiChatService.java
   ├─ QwenImageGenerationService.java
   ├─ QwenImageUnderstandingService.java
   ├─ QwenSpeechRecognitionService.java
   ├─ QwenSpeechSynthesisService.java
   ├─ RedisMemoryService.java
   ├─ WechatMessagePollingService.java
   ├─ WechatMessageServiceImpl.java
   └─ WechatMessageTextExtractor.java
```

入口类仍是：

```text
WechatAI.AdvancedBotDemo
```

## 3. 模型与配置改造

### 实现内容

统一使用千问相关模型，并通过配置文件管理：

```properties
qwen.api.key=${QWEN_API_KEY}
qwen.workspace.id=${QWEN_WORKSPACE_ID}

qwen.text.model=qwen3.7-max
qwen.vision.model=qwen3.5-ocr
qwen.image.generation.model=qwen-image-plus
qwen.speech.recognition.model=fun-asr-flash-2026-06-15
qwen.speech.synthesis.model=qwen3-tts-flash
qwen.speech.synthesis.voice=Cherry
```

API Key 不再硬编码，统一通过环境变量：

```text
QWEN_API_KEY
QWEN_WORKSPACE_ID
REDIS_PASSWORD
```

### 遇到的问题

用户在 Windows 中设置了环境变量，但是 IDEA 小三角运行时仍提示大模型未配置。

### 原因

Windows 的 `setx` 只会写入后续新进程的环境变量。已经打开的 IDEA 进程不会自动刷新环境变量，所以 Java 程序拿不到新设置的 `QWEN_API_KEY`。

### 解决方式

- 让用户重启 IDEA 或重新打开终端。
- 配置文件中继续使用 `${QWEN_API_KEY}` 占位符。
- `AppConfigLoader` 支持从配置文件与系统环境变量中解析 `${...}`。

## 4. 图片能力

### 实现内容

图片相关能力拆成两个服务：

- `ImageUnderstandingService`
- `ImageGenerationService`

对应实现：

- `QwenImageUnderstandingService`
- `QwenImageGenerationService`

图片理解使用视觉模型，图片生成使用生图模型。

### 遇到的问题

一开始希望只接一个 `qwen3.7-max` 模型处理所有能力。

### 原因

`qwen3.7-max` 主要用于文本对话，不适合直接承担图片输入和图片生成能力。图片理解、图片生成需要分别使用支持视觉或生图的模型。

### 解决方式

保留千问体系，但按能力拆模型：

- 文本：`qwen3.7-max`
- 图片理解：`qwen3.5-ocr`
- 图片生成：`qwen-image-plus`

## 5. 语音识别 ASR

### 实现内容

新增：

- `SpeechRecognitionService`
- `QwenSpeechRecognitionService`

微信语音下载后先判断格式。微信语音常见为 Silk V3，需要先转成 WAV，再提交给 ASR 模型：

```text
fun-asr-flash-2026-06-15
```

### 遇到的问题

语音识别早期失败。

### 原因

微信语音不是普通 mp3/wav，而是 Silk 编码。如果直接把微信语音 bytes 提交给 ASR，模型无法正确识别。

### 解决方式

引入 Silk 解码依赖，把微信 Silk 语音转成 WAV 后再识别。

最终日志中已经能看到：

```text
检测到微信 Silk 语音，开始转码为 wav
语音识别结果: ...
```

## 6. 语音生成 TTS

### 实现内容

新增：

- `SpeechSynthesisService`
- `QwenSpeechSynthesisService`
- `VoiceReply`

最终方案改为 HTTP TTS：

```properties
qwen.speech.synthesis.api.url=https://dashscope.aliyuncs.com/api/v1/services/aigc/multimodal-generation/generation
qwen.speech.synthesis.model=qwen3-tts-flash
qwen.speech.synthesis.voice=Cherry
```

生成后下载音频文件，再通过：

```java
client.sendFile(...)
```

发送给用户。

### 遇到的问题 1：WebSocket 语音生成卡住

日志停在：

```text
语音生成WebSocket已连接
```

后面没有继续返回。

### 原因

早期误用了实时 WebSocket 协议，代码等待 `session.created` 等事件，但当前模型接口并没有按这个事件流返回。

### 解决方式

尝试切回 `api-ws/v1/inference/` 的 `run-task / continue-task / finish-task` 协议。

### 遇到的问题 2：Missing required parameter payload.input

百炼返回：

```text
Missing required parameter 'payload.input'
```

### 原因

WebSocket 首包缺少服务端要求的 `payload.input` 字段。

### 解决方式

给 `run-task` 首包补充 `payload.input`。

### 遇到的问题 3：url error

百炼继续返回：

```text
url error, please check url！
```

### 原因

`qwen-audio-3.0-tts-plus` 的 WebSocket 路线与当前“纯文本生成音频文件再发微信”的使用方式不稳定，接口协议和模型适配成本较高。

### 解决方式

放弃 WebSocket TTS，切到更稳定的 HTTP TTS：

```text
qwen3-tts-flash
```

HTTP TTS 返回音频 URL，程序下载音频 bytes 后发送文件。

### 遇到的问题 4：返回的是 WAV，不是 MP3

### 原因

HTTP TTS 默认可能返回 WAV 音频。

### 解决方式

在 TTS 请求中显式增加输出格式参数：

```json
{
  "parameters": {
    "format": "mp3",
    "audio_format": "mp3",
    "response_format": "mp3"
  }
}
```

同时下载后根据 `Content-Type` 判断文件名：

- `audio/mpeg` -> `qwen-tts.mp3`
- `audio/wav` -> `qwen-tts.wav`

### 遇到的问题 5：生成内容不是用户指定内容

用户说：

```text
给我生成一个你好的语音
```

实际 TTS 读出来的是 AI 的回复，而不是“你好”。

### 原因

语音消息识别成功后，代码直接把识别文本丢给 `AiChatService` 聊天，再把聊天回复拿去 TTS。

也就是说流程是：

```text
语音识别文本 -> 聊天模型 -> TTS
```

而用户期望是：

```text
语音识别文本 -> 提取“要朗读的内容” -> TTS
```

### 解决方式

在语音识别成功后，先判断是否是语音生成指令：

```java
if (isSpeechSynthesisRequest(recognizedText)) {
    handleSpeechSynthesis(fromUserId, normalizeSpeechPrompt(recognizedText));
    return true;
}
```

并优化 `normalizeSpeechPrompt`，把：

```text
给我生成一个你好的语音
```

提取为：

```text
你好
```

## 7. MP3 文件发送

### 实现内容

用户要求生成语音后直接发送 mp3 文件，不走微信语音消息。

最终使用 SDK 方法：

```java
client.sendFile(fromUserId, audioBytes, fileName, mimeType);
```

### 遇到的问题

早期使用的是：

```java
client.sendVoice(...)
```

这会走微信语音消息格式，不符合“直接发送 mp3 文件”的需求。

### 解决方式

通过 `javap` 检查 `ILinkClient`，确认 SDK 支持：

```java
sendFile(String, byte[], String, String)
```

于是改成文件发送。

## 8. 正在输入中

### 实现内容

模型调用期间显示“对方正在输入中”。

新增 `TypingIndicator` 内部类：

- 开始处理消息时调用 `client.startTyping`
- 每 4 秒续一次状态
- 处理结束后调用 `client.stopTyping`

日志：

```text
已发送正在输入状态
已关闭正在输入状态
```

### 遇到的问题

只调用一次 `startTyping` 时，微信端看不到或很快消失。

### 原因

微信输入状态是短时状态，只发一次可能过期，也可能被客户端刷新机制忽略。

### 解决方式

在消息处理期间循环保活，每 4 秒重新发送一次正在输入状态。

## 9. 异步多线程处理

### 实现内容

原始消息处理流程是串行的：

```text
getUpdates -> handleAndReply -> 下一个消息
```

如果用户让机器人生成语音，这个慢任务会阻塞后续普通聊天。

改造后：

```text
getUpdates -> dispatchMessage -> 线程池处理
```

轮询线程只负责拉消息，不再直接执行慢任务。

### 第一版方案

固定线程池：

```text
4 个工作线程
```

### 遇到的问题

用户反馈多线程处理“还是有点跟不上”。

### 原因

固定 4 线程在多个慢任务并发时仍可能排队；另外轮询间隔 1000ms，消息拉取响应也偏慢。

### 解决方式

升级为弹性线程池：

```text
核心线程数：4
最大线程数：12
队列容量：200
轮询间隔：200ms
```

队列堆积时打印：

```text
消息进入异步队列: active=..., pool=..., queue=...
```

## 10. Redis 短期记忆

### 实现内容

新增 Redis 短期记忆，用于普通聊天上下文。

新增文件：

```text
model/ChatMessage.java
model/MemoryRecord.java
service/MemoryService.java
service/impl/RedisMemoryService.java
```

配置：

```properties
redis.host=127.0.0.1
redis.port=6379
redis.password=${REDIS_PASSWORD}
redis.database=0

memory.enabled=true
memory.max.messages=20
memory.ttl.seconds=1800
```

Redis Key：

```text
wechat:memory:{fromUserId}
```

Redis 数据结构：

```text
List
```

每次普通聊天写入两条：

- user
- assistant

### 调用链

普通文本聊天：

```text
loadRecentMessages -> aiChatService.chat(userMessage, history) -> appendConversation
```

语音识别后的普通聊天也接入记忆。

工具型指令不写入记忆：

- 语音生成
- 图片生成

### 遇到的问题

项目当前不是完整 Spring Bean 注入方式，而是 `AdvancedBotDemo` 中手动装配服务。

### 原因

虽然项目使用了 SpringBoot 依赖，但实际服务对象并没有交给 Spring 容器管理。

### 解决方式

在 `AdvancedBotDemo` 中手动创建：

```java
MemoryService memoryService = new RedisMemoryService(aiProperties);
```

并传入：

```java
new WechatMessageServiceImpl(..., memoryService)
```

### Redis 不可用的处理

`RedisMemoryService` 中所有 Redis 读写都捕获异常：

- Redis 读取失败：降级为无记忆聊天。
- Redis 写入失败：打印日志并忽略。
- Redis 清空失败：打印日志。

这样 Redis 挂了不会导致机器人无法聊天。

## 11. 清空记忆指令

新增指令：

```text
清空记忆
忘记上下文
重置对话
清除记忆
```

触发后删除当前用户的 Redis 记忆：

```java
memoryService.clear(fromUserId);
```

并回复：

```text
好的，我已经清空这段对话记忆了。
```

## 12. 最终验证

今日多次执行编译验证：

```powershell
.\mvnw.cmd -q -DskipTests compile
```

最终编译通过。

## 13. 当前已知注意事项

### 1. Redis 需要单独启动

本地可使用：

```powershell
docker run --name wechat-bot-redis -p 6379:6379 -d redis:7
```

如果 Redis 没启动，机器人仍能聊天，但没有短期记忆。

### 2. TTS 是否一定返回 MP3 取决于百炼接口

代码已经请求 MP3，但如果服务端仍返回 WAV，当前程序会按真实 `Content-Type` 命名为 `.wav`。

### 3. 当前并发是全局并发

现在不同用户、同一用户的多条消息都会进入全局线程池。

这能提高吞吐，但同一个用户连续快速发送多条消息时，可能出现上下文顺序不完全稳定。

后续可升级为：

```text
同一用户串行，不同用户并发
```

### 4. 记忆只接入普通聊天

目前以下内容不会写入记忆：

- 图片生成
- 语音生成
- 工具型指令

这是为了避免上下文污染。

## 14. 后续建议

建议下一步继续优化：

1. 增加“同用户串行消息队列”，解决多线程下单用户上下文顺序问题。
2. 给 TTS 增加本地格式转换，确保最终一定发 MP3。
3. 给 Redis 记忆增加摘要机制，超过 20 条后压缩旧上下文。
4. 给图片理解结果增加可选记忆开关。
5. 把当前手动装配逐步迁移到真正 Spring Bean 管理。
