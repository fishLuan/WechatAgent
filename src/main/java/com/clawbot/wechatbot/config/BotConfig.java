package com.clawbot.wechatbot.config;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 机器人配置读取器。
 *
 * 所有配置项及默认值均位于 application.properties；本类只负责加载配置、
 * 解析 ${环境变量:默认值} 占位符并提供类型安全的 getter。
 */
public class BotConfig {
    private static final String CONFIG_FILE = "application.properties";
    private static final Pattern PLACEHOLDER = Pattern.compile("^\\$\\{([^:}]+):(.*)}$", Pattern.DOTALL);

    private final Properties properties;

    public BotConfig() {
        this.properties = loadProperties();
    }

    public String getDeepSeekApiKey() {
        return get("deepseek.api.key");
    }

    public String getDeepSeekModel() {
        return get("deepseek.model");
    }

    public String getDeepSeekUrl() {
        return get("deepseek.url");
    }

    public String getSystemPrompt() {
        return get("bot.system.prompt");
    }

    public double getDeepSeekTemperature() {
        return getDouble("deepseek.temperature");
    }

    public int getDeepSeekMaxTokens() {
        return getInt("deepseek.max-tokens");
    }

    public int getDeepSeekMaxToolRounds() {
        return getInt("deepseek.max-tool-rounds");
    }

    public int getDeepSeekConnectTimeoutSeconds() {
        return getInt("deepseek.connect-timeout-seconds");
    }

    public int getDeepSeekRequestTimeoutSeconds() {
        return getInt("deepseek.request-timeout-seconds");
    }

    public String getDashscopeApiKey() {
        return get("dashscope.api.key");
    }

    public String getDashscopeEndpoint() {
        return get("dashscope.multimodal.url");
    }

    public int getDashscopeConnectTimeoutSeconds() {
        return getInt("dashscope.connect-timeout-seconds");
    }

    public int getDashscopeRequestTimeoutSeconds() {
        return getInt("dashscope.request-timeout-seconds");
    }

    // 博查AI Web 搜索
    public String getBochaApiKey() { return get("bocha.api.key"); }
    public String getBochaEndpoint() { return get("bocha.web-search.url"); }
    public int getBochaConnectTimeoutSeconds() { return getInt("bocha.connect-timeout-seconds"); }
    public int getBochaRequestTimeoutSeconds() { return getInt("bocha.request-timeout-seconds"); }
    public boolean isBochaConfigured() { return !getBochaApiKey().isBlank(); }

    public String getVisionModel() { return get("dashscope.vision.model"); }
    public String getVisionDefaultQuestion() { return get("dashscope.vision.default-question"); }
    public String getImageModel() { return get("dashscope.image.model"); }
    public String getImageDefaultSize() { return get("dashscope.image.default-size"); }
    public int getImageDefaultCount() { return getInt("dashscope.image.default-count"); }
    public boolean isImagePromptExtend() { return getBoolean("dashscope.image.prompt-extend"); }
    public boolean isImageWatermark() { return getBoolean("dashscope.image.watermark"); }
    public String getTtsModel() { return get("dashscope.tts.model"); }
    public String getTtsDefaultVoice() { return get("dashscope.tts.default-voice"); }
    public String getTtsFormat() { return get("dashscope.tts.format"); }
    public int getTtsMaxTextLength() { return getInt("dashscope.tts.max-text-length"); }

    public String getAmapWeatherApiKey() {
        return get("amap.weather.api.key");
    }

    public String getAmapWeatherEndpoint() { return get("amap.weather.url"); }
    public int getAmapConnectTimeoutSeconds() { return getInt("amap.weather.connect-timeout-seconds"); }
    public int getAmapRequestTimeoutSeconds() { return getInt("amap.weather.request-timeout-seconds"); }

    public int getLoginTimeoutMs() {
        String value = get("wechat.login.timeout-ms");
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new IllegalStateException("配置 wechat.login.timeout-ms 必须是整数，当前值：" + value, e);
        }
    }

    public boolean isDeepSeekConfigured() {
        return !getDeepSeekApiKey().isBlank();
    }

    public boolean isDashscopeConfigured() {
        return !getDashscopeApiKey().isBlank();
    }

    public boolean isAmapWeatherConfigured() {
        return !getAmapWeatherApiKey().isBlank();
    }

    public String getJuheExchangeApiKey() { return get("juhe.exchange.api.key"); }
    public String getJuheExchangeEndpoint() { return get("juhe.exchange.url"); }
    public String getJuheExchangeVersion() { return get("juhe.exchange.version"); }
    public int getJuheExchangeConnectTimeoutSeconds() { return getInt("juhe.exchange.connect-timeout-seconds"); }
    public int getJuheExchangeRequestTimeoutSeconds() { return getInt("juhe.exchange.request-timeout-seconds"); }

    public boolean isJuheExchangeConfigured() {
        return !getJuheExchangeApiKey().isBlank();
    }

    private String get(String key) {
        // JVM -D 参数优先级最高，便于部署时临时覆盖 application.properties。
        String systemValue = System.getProperty(key);
        if (systemValue != null) return systemValue.trim();

        String value = properties.getProperty(key);
        if (value == null) throw new IllegalStateException("缺少配置项：" + key);
        return resolvePlaceholder(value.trim());
    }

    private int getInt(String key) {
        String value = get(key);
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new IllegalStateException("配置 " + key + " 必须是整数，当前值：" + value, e);
        }
    }

    private double getDouble(String key) {
        String value = get(key);
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException e) {
            throw new IllegalStateException("配置 " + key + " 必须是数字，当前值：" + value, e);
        }
    }

    private boolean getBoolean(String key) {
        String value = get(key);
        if ("true".equalsIgnoreCase(value)) return true;
        if ("false".equalsIgnoreCase(value)) return false;
        throw new IllegalStateException("配置 " + key + " 必须是 true 或 false，当前值：" + value);
    }

    private String resolvePlaceholder(String value) {
        Matcher matcher = PLACEHOLDER.matcher(value);
        if (!matcher.matches()) return value;
        String environmentValue = System.getenv(matcher.group(1));
        return environmentValue != null ? environmentValue.trim() : matcher.group(2).trim();
    }

    private static Properties loadProperties() {
        Properties result = new Properties();
        ClassLoader loader = BotConfig.class.getClassLoader();
        try (InputStream stream = loader.getResourceAsStream(CONFIG_FILE)) {
            if (stream == null) {
                throw new IllegalStateException("classpath 中找不到 " + CONFIG_FILE);
            }
            // Properties.load(InputStream) 默认按 ISO-8859-1 读取，显式使用 UTF-8 以支持中文提示词。
            result.load(new InputStreamReader(stream, StandardCharsets.UTF_8));
            return result;
        } catch (IOException e) {
            throw new IllegalStateException("读取 " + CONFIG_FILE + " 失败", e);
        }
    }
}
