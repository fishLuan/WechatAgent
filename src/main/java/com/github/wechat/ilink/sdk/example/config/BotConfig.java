package com.github.wechat.ilink.sdk.example.config;

/**
 * 机器人配置：集中管理所有外部配置（API Key、模型名、URL 等）
 * 不硬编码密钥，从环境变量或系统属性读取
 *
 * 使用方式：
 *   BotConfig config = new BotConfig();
 *   String key = config.getDeepSeekApiKey();
 */
public class BotConfig {

    // ============== DeepSeek 文本对话 ==============
    private final String deepSeekApiKey;
    private final String deepSeekModel;
    private final String deepSeekUrl;
    private final String systemPrompt;

    // ============== 阿里云百炼 图片理解+文生图 ==============
    private final String dashscopeApiKey;

    // ============== 微信 SDK ==============
    private final int loginTimeoutMs;

    public BotConfig() {
        this.deepSeekApiKey = firstNonNull(
            System.getenv("DEEPSEEK_API_KEY"),
            System.getProperty("deepseek.api.key"),
            "");
        this.deepSeekModel = firstNonNull(
            System.getenv("DEEPSEEK_MODEL"),
            System.getProperty("deepseek.model"),
            "deepseek-chat");
        this.deepSeekUrl = firstNonNull(
            System.getenv("DEEPSEEK_URL"),
            System.getProperty("deepseek.url"),
            "https://api.deepseek.com/v1/chat/completions");

        this.dashscopeApiKey = firstNonNull(
            System.getenv("DASHSCOPE_API_KEY"),
            System.getProperty("dashscope.api.key"),
            "");

        this.systemPrompt = "你是一个友好、幽默、有耐心的微信机器人助手，目前会画图，生成语音，PDF或Word文档"
            + "你的名字叫ClawBot。"
            + "用话痨又自然的中文回答用户。"
            + "回答不要太长，控制在3句话以内。"
            + "不要讨论或编造你使用的底层技术、模型架构或API供应商。"
            + "当用户问你是什么模型时，用幽默的方式回答，比如'我是一个训练有素的语言模型小助手～'。";

        this.loginTimeoutMs = 180000;
    }

    // ============ Getter ============
    public String getDeepSeekApiKey() { return deepSeekApiKey; }
    public String getDeepSeekModel() { return deepSeekModel; }
    public String getDeepSeekUrl() { return deepSeekUrl; }
    public String getSystemPrompt() { return systemPrompt; }
    public String getDashscopeApiKey() { return dashscopeApiKey; }
    public int getLoginTimeoutMs() { return loginTimeoutMs; }

    public boolean isDeepSeekConfigured() {
        return deepSeekApiKey != null && !deepSeekApiKey.trim().isEmpty();
    }

    public boolean isDashscopeConfigured() {
        return dashscopeApiKey != null && !dashscopeApiKey.trim().isEmpty();
    }

    // ============ 工具 ============
    private static String firstNonNull(String... items) {
        for (String s : items) {
            if (s != null && !s.trim().isEmpty()) return s;
        }
        return "";
    }
}