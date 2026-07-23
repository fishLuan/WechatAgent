# Function tools reuse guide

`com.clawbot.wechatbot.tools` 下面的工具可以脱离微信机器人业务使用。外部项目只需要 Java 17、`jackson-databind`，以及具体工具自己的网络权限或 API Key。

## Minimal registry

```java
ObjectMapper mapper = new ObjectMapper();

FunctionToolRegistry registry = FunctionToolRegistry.builder()
    .mapper(mapper)
    .logger(FunctionToolLogger.NOOP)
    .register(new WebPageExtractTool(10, 15, 6000))
    .register(new AmapWeatherTool(System.getenv("AMAP_WEATHER_API_KEY")))
    .build();

ArrayNode toolDefinitions = registry.definitions();
String toolResult = registry.execute("extract_web_page", "{\"url\":\"https://example.com\"}");
```

## Custom dependencies

外部项目如果已经有自己的 `ObjectMapper`、`HttpClient`、超时配置或日志系统，可以直接注入，避免工具类反向依赖本项目配置。

```java
HttpClient httpClient = HttpClient.newBuilder()
    .connectTimeout(Duration.ofSeconds(5))
    .followRedirects(HttpClient.Redirect.NORMAL)
    .build();

FunctionToolLogger logger = message -> yourLogger.info(message);

WebPageExtractClient pageClient = new WebPageExtractClient(
    httpClient,
    Duration.ofSeconds(20),
    8000,
    "YourApp-WebPageTool/1.0"
);

FunctionTool pageTool = new WebPageExtractTool(pageClient, mapper, 8000, logger);
```

## Contract

每个工具实现 `FunctionTool`：

- `name()` 返回模型调用的函数名。
- `definition()` 返回兼容 OpenAI/DeepSeek function-calling 的 JSON schema。
- `execute(JsonNode arguments)` 执行工具，并返回可直接回传给模型的 JSON 字符串。

`FunctionToolRegistry` 负责汇总 schema、按名称路由执行、捕获异常并返回统一错误 JSON。
