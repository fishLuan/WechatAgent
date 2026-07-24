package com.clawbot.wechatbot.config;

import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Spring 管理的类型安全配置门面。
 *
 * 配置值统一来自 application.properties，并由 Spring 自动解析环境变量占位符、
 * JVM -D 参数和其他 PropertySource。
 */
@Component
public class BotConfig {
    private final Environment environment;

    public BotConfig(Environment environment) {
        this.environment = environment;
    }

    public String getDeepSeekApiKey() { return get("deepseek.api.key"); }
    public String getDeepSeekModel() { return get("deepseek.model"); }
    public String getDeepSeekUrl() { return get("deepseek.url"); }
    public String getSystemPrompt() { return get("bot.system.prompt"); }
    public double getDeepSeekTemperature() { return getDouble("deepseek.temperature"); }
    public int getDeepSeekMaxTokens() { return getInt("deepseek.max-tokens"); }
    public int getDeepSeekMaxToolRounds() { return getInt("deepseek.max-tool-rounds"); }
    public int getDeepSeekConnectTimeoutSeconds() { return getInt("deepseek.connect-timeout-seconds"); }
    public int getDeepSeekRequestTimeoutSeconds() { return getInt("deepseek.request-timeout-seconds"); }

    public String getDashscopeApiKey() { return get("dashscope.api.key"); }
    public String getDashscopeEndpoint() { return get("dashscope.multimodal.url"); }
    public int getDashscopeConnectTimeoutSeconds() { return getInt("dashscope.connect-timeout-seconds"); }
    public int getDashscopeRequestTimeoutSeconds() { return getInt("dashscope.request-timeout-seconds"); }
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

    public String getBochaApiKey() { return get("bocha.api.key"); }
    public String getBochaEndpoint() { return get("bocha.web-search.url"); }
    public int getBochaConnectTimeoutSeconds() { return getInt("bocha.connect-timeout-seconds"); }
    public int getBochaRequestTimeoutSeconds() { return getInt("bocha.request-timeout-seconds"); }

    public String getAmapWeatherApiKey() { return get("amap.weather.api.key"); }
    public String getAmapWeatherEndpoint() { return get("amap.weather.url"); }
    public int getAmapConnectTimeoutSeconds() { return getInt("amap.weather.connect-timeout-seconds"); }
    public int getAmapRequestTimeoutSeconds() { return getInt("amap.weather.request-timeout-seconds"); }

    public String getJuheExchangeApiKey() { return get("juhe.exchange.api.key"); }
    public String getJuheExchangeEndpoint() { return get("juhe.exchange.url"); }
    public String getJuheExchangeVersion() { return get("juhe.exchange.version"); }
    public int getJuheExchangeConnectTimeoutSeconds() { return getInt("juhe.exchange.connect-timeout-seconds"); }
    public int getJuheExchangeRequestTimeoutSeconds() { return getInt("juhe.exchange.request-timeout-seconds"); }

    public int getWebPageExtractConnectTimeoutSeconds() {
        return getInt("webpage.extract.connect-timeout-seconds");
    }

    public int getWebPageExtractRequestTimeoutSeconds() {
        return getInt("webpage.extract.request-timeout-seconds");
    }

    public int getWebPageExtractMaxBodyChars() {
        return getInt("webpage.extract.max-body-chars");
    }

    public String getTianapiApiKey() { return get("tianapi.api.key"); }
    public int getLoginTimeoutMs() { return getInt("wechat.login.timeout-ms"); }
    public int getMaxSessions() { return getInt("wechat.max-sessions"); }

    public boolean isDingTalkNotificationEnabled() {
        return getBoolean("notification.dingtalk.enabled");
    }
    public String getDingTalkWebhook() { return get("notification.dingtalk.webhook"); }
    public String getDingTalkSecret() { return get("notification.dingtalk.secret"); }
    public int getDingTalkTimeoutSeconds() {
        return getInt("notification.dingtalk.timeout-seconds");
    }
    public int getDingTalkErrorDeduplicateSeconds() {
        return getInt("notification.dingtalk.error-deduplicate-seconds");
    }

    public boolean isDeepSeekConfigured() { return !getDeepSeekApiKey().isBlank(); }
    public boolean isDashscopeConfigured() { return !getDashscopeApiKey().isBlank(); }
    public boolean isAmapWeatherConfigured() { return !getAmapWeatherApiKey().isBlank(); }
    public boolean isJuheExchangeConfigured() { return !getJuheExchangeApiKey().isBlank(); }
    public boolean isBochaConfigured() { return !getBochaApiKey().isBlank(); }
    public boolean isTianapiConfigured() { return !getTianapiApiKey().isBlank(); }
    public boolean isDingTalkNotificationConfigured() {
        return isDingTalkNotificationEnabled() && !getDingTalkWebhook().isBlank();
    }

    private String get(String key) {
        String value = environment.getProperty(key);
        if (value == null) throw new IllegalStateException("缺少配置项：" + key);
        return value.trim();
    }

    private int getInt(String key) {
        try {
            return Integer.parseInt(get(key));
        } catch (NumberFormatException e) {
            throw new IllegalStateException("配置 " + key + " 必须是整数", e);
        }
    }

    private double getDouble(String key) {
        try {
            return Double.parseDouble(get(key));
        } catch (NumberFormatException e) {
            throw new IllegalStateException("配置 " + key + " 必须是数字", e);
        }
    }

    private boolean getBoolean(String key) {
        String value = get(key);
        if ("true".equalsIgnoreCase(value)) return true;
        if ("false".equalsIgnoreCase(value)) return false;
        throw new IllegalStateException("配置 " + key + " 必须是 true 或 false");
    }
}
