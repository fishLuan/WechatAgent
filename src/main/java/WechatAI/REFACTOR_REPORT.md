# AdvancedBotDemo 解耦分层报告

## 一、改造目标

原始 `AdvancedBotDemo.java` 同时承担启动入口、配置读取、微信客户端初始化、登录处理、消息轮询、消息解析、AI 接口调用和回复发送等职责。代码可以运行，但后续维护、测试和扩展都会越来越困难。

本次参考 Spring Boot 常见架构模式进行分层：入口类只负责编排启动流程，配置集中到 `config` 层，业务逻辑集中到 `service` 层，各模块通过构造函数依赖注入，降低静态全局变量和方法之间的耦合。

## 二、分层结果

```text
WechatAI
├── AdvancedBotDemo.java
├── config
│   ├── AiProperties.java
│   ├── AppConfigLoader.java
│   └── WechatClientFactory.java
├── service
│   ├── AiChatService.java
│   ├── MessagePollingService.java
│   ├── MessageTextExtractor.java
│   └── WechatMessageService.java
└── REFACTOR_REPORT.md
```

## 三、类职责说明

### `AdvancedBotDemo`

作为应用启动入口，负责按顺序编排：

1. 加载配置。
2. 创建微信客户端。
3. 执行扫码登录。
4. 创建服务对象。
5. 启动消息轮询。
6. 保持主线程运行。

### `config/AiProperties`

封装 DeepSeek API 所需配置，包括：

- `apiKey`
- `apiUrl`
- `model`
- `systemPrompt`

### `config/AppConfigLoader`

负责从 `config.properties` 读取配置、设置默认值、校验 API Key，并打印配置加载结果。配置读取逻辑从启动入口中独立出来后，后续可以很自然地替换为 Spring Boot 的 `@ConfigurationProperties`。

### `config/WechatClientFactory`

负责创建 `ILinkClient`，集中管理微信 SDK 的连接参数、重试参数、心跳配置和登录监听器。

### `service/AiChatService`

负责调用大模型接口，包括：

- 构建 Chat Completions 请求体。
- 发送 HTTP 请求。
- 解析模型回复。
- 处理 API 调用异常。

### `service/MessageTextExtractor`

负责从 `WeixinMessage` 中提取文本内容，避免消息结构解析逻辑散落在业务流程中。

### `service/WechatMessageService`

负责单条消息的处理与回复，包括：

- 读取发送人。
- 提取文本。
- 跳过空消息。
- 调用 AI 服务生成回复。
- 使用备用回复兜底。
- 调用微信客户端发送消息。

### `service/MessagePollingService`

负责轮询微信消息，并将每条消息交给 `WechatMessageService` 处理。轮询间隔、异常重试间隔和线程名称都集中在该类中。

## 四、主要改造点

1. 删除 `AdvancedBotDemo` 中的大量静态全局状态，将配置和服务对象实例化。
2. 将配置读取拆到 `AppConfigLoader`，将配置值封装为 `AiProperties`。
3. 将微信客户端构建拆到 `WechatClientFactory`。
4. 将 AI HTTP 调用拆到 `AiChatService`。
5. 将消息文本提取拆到 `MessageTextExtractor`。
6. 将消息处理和回复发送拆到 `WechatMessageService`。
7. 将消息轮询线程拆到 `MessagePollingService`。
8. 保留原有业务行为和日志输出，避免大范围改变运行方式。

## 五、对 Spring Boot 迁移的铺垫

当前项目还不是标准 Spring Boot 工程，因此本次没有强行添加 Spring Boot 依赖，而是采用 Spring Boot 风格的分层和依赖注入写法。未来如果迁移到 Spring Boot，可以按如下方式演进：

- `AiProperties` 改为 `@ConfigurationProperties(prefix = "deepseek")`。
- `WechatClientFactory` 改为 `@Configuration` + `@Bean`。
- `AiChatService`、`WechatMessageService`、`MessagePollingService` 改为 `@Service`。
- `AdvancedBotDemo` 改为 `@SpringBootApplication`，使用 `CommandLineRunner` 启动登录和轮询流程。

## 六、收益

- 启动流程更清晰。
- 单个类职责更单一。
- AI 调用、消息处理、消息轮询可以分别测试。
- 后续替换模型供应商或微信 SDK 配置时影响范围更小。
- 代码结构更接近 Spring Boot 项目的 controller/service/config/model 分层习惯。
