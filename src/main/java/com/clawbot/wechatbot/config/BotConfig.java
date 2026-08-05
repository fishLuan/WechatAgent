package com.clawbot.wechatbot.config;

import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

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
    public boolean isLongFormEnabled() { return getBoolean("deepseek.long-form.enabled"); }
    public int getLongFormMinTargetChars() {
        return getInt("deepseek.long-form.min-target-chars");
    }
    public int getLongFormMaxTargetChars() {
        return getInt("deepseek.long-form.max-target-chars");
    }
    public int getLongFormTolerancePercent() {
        return getInt("deepseek.long-form.tolerance-percent");
    }
    public int getLongFormMaxContinuationRounds() {
        return getInt("deepseek.long-form.max-continuation-rounds");
    }
    public int getLongFormMaxTotalChars() {
        return getInt("deepseek.long-form.max-total-chars");
    }
    public boolean isAgentEnabled() {
        return getBoolean("agent.enabled");
    }
    public int getAgentMaxPlannedTasks() {
        return getInt("agent.max-planned-tasks");
    }
    public int getAgentMaxTasksPerBatch() {
        return getInt("agent.max-tasks-per-batch");
    }
    public int getAgentMaxParallelism() {
        return getInt("agent.max-parallelism");
    }
    public int getAgentMaxOuterRounds() {
        return getInt("agent.max-outer-rounds");
    }
    public int getAgentMaxChatDepth() {
        return getInt("agent.guard.max-chat-depth");
    }
    public int getAgentMaxToolCallsPerRound() {
        return getInt("agent.guard.max-tool-calls-per-round");
    }
    public int getAgentMaxTotalToolCalls() {
        return getInt("agent.guard.max-total-tool-calls");
    }
    public int getAgentMaxSameToolFailures() {
        return getInt("agent.guard.max-same-tool-failures");
    }
    public int getAgentMaxToolResultChars() {
        return getInt("agent.guard.max-tool-result-chars");
    }
    public int getAgentMaxTotalToolResultChars() {
        return getInt("agent.guard.max-total-tool-result-chars");
    }
    public int getAgentExecutionTimeoutSeconds() {
        return getInt("agent.guard.execution-timeout-seconds");
    }
    public double getAgentToolValidationMinConfidence() {
        return getDouble("agent.validation.min-confidence");
    }
    public boolean isAgentReplanEnabled() {
        return getBoolean("agent.replan.enabled");
    }
    public int getAgentReplanMaxCount() {
        return getInt("agent.replan.max-count");
    }
    public int getAgentReplanMaxMutations() {
        return getInt("agent.replan.max-mutations");
    }
    public int getAgentReplanMaxGeneratedTasks() {
        return getInt("agent.replan.max-generated-tasks");
    }
    public int getAgentReplanMaxTotalTasks() {
        return getInt("agent.replan.max-total-tasks");
    }
    public int getAgentReplanMaxRetriesPerTask() {
        return getInt("agent.replan.max-retries-per-task");
    }
    public int getAgentReplanMaxTotalTaskExecutions() {
        return getInt("agent.replan.max-total-task-executions");
    }
    public int getAgentReplanTimeoutSeconds() {
        return getInt("agent.replan.timeout-seconds");
    }
    public int getAgentReferenceMaxPerTask() {
        return getInt("agent.reference.max-per-task");
    }
    public int getAgentReferenceMaxDepth() {
        return getInt("agent.reference.max-depth");
    }
    public int getAgentReferenceMaxPathLength() {
        return getInt("agent.reference.max-path-length");
    }
    public int getAgentReferenceMaxResolvedInputChars() {
        return getInt("agent.reference.max-resolved-input-chars");
    }
    public int getAgentMaxInputAttachments() {
        return getInt("agent.input.max-attachments");
    }
    public int getAgentMaxSingleInputBytes() {
        return getInt("agent.input.max-single-bytes");
    }
    public int getAgentMaxTotalInputBytes() {
        return getInt("agent.input.max-total-bytes");
    }
    public int getAgentMaxDocumentChars() {
        return getInt("agent.input.max-document-chars");
    }
    public String getSkillClasspathPattern() {
        return get("agent.skills.classpath-pattern");
    }
    public String getSkillExternalDirectory() {
        return get("agent.skills.external-directory");
    }
    public boolean isSkillWatchEnabled() {
        return getBoolean("agent.skills.watch-enabled");
    }
    public int getSkillReloadDebounceMillis() {
        return getInt("agent.skills.reload-debounce-millis");
    }
    public int getSkillMaxCount() {
        return getInt("agent.skills.max-count");
    }
    public int getSkillMaxDefinitionBytes() {
        return getInt("agent.skills.max-definition-bytes");
    }
    public int getDeepSeekConnectTimeoutSeconds() { return getInt("deepseek.connect-timeout-seconds"); }
    public int getDeepSeekRequestTimeoutSeconds() { return getInt("deepseek.request-timeout-seconds"); }
    public int getDeepSeekTransientRetries() { return getInt("deepseek.transient-retries"); }
    public int getDeepSeekCircuitBreakSeconds() { return getInt("deepseek.circuit-break-seconds"); }

    public String getDashscopeApiKey() { return get("dashscope.api.key"); }
    public String getDashscopeEndpoint() { return get("dashscope.multimodal.url"); }
    public String getDashscopeEmbeddingEndpoint() { return get("dashscope.embedding.url"); }
    public String getDashscopeEmbeddingModel() { return get("dashscope.embedding.model"); }
    public int getDashscopeEmbeddingDimension() { return getInt("dashscope.embedding.dimension"); }
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
    public int getWebPageExtractMaxResponseBytes() {
        return getInt("webpage.extract.max-response-bytes");
    }
    public int getWebPageExtractMaxRedirects() {
        return getInt("webpage.extract.max-redirects");
    }
    public String getWebPageExtractAllowedPorts() {
        return get("webpage.extract.allowed-ports");
    }

    public String getTianapiApiKey() { return get("tianapi.api.key"); }
    public int getLoginTimeoutMs() { return getInt("wechat.login.timeout-ms"); }
    public int getMessageDispatchParallelism() {
        return getInt("wechat.dispatch.parallelism");
    }
    public int getMessageDispatchMaxPending() {
        return getInt("wechat.dispatch.max-pending-messages");
    }
    public int getMessageDispatchShutdownWaitSeconds() {
        return getInt("wechat.dispatch.shutdown-wait-seconds");
    }
    public int getLongReplyThreshold() { return getInt("wechat.reply.long-text-threshold"); }
    public int getLongReplyChunkSize() { return getInt("wechat.reply.chunk-size"); }
    public int getLongReplyPendingExpireMinutes() {
        return getInt("wechat.reply.pending-expire-minutes");
    }
    public int getLongReplyMaxPendingChars() {
        return getInt("wechat.reply.max-pending-chars");
    }

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
        return normalizeUtf8Value(value.trim());
    }

    /**
     * Spring 的传统 .properties 加载链可能把 UTF-8 中文按 ISO-8859-1 解码。
     * 仅当重新解码后能得到更多中文字符时采用修复结果，避免改动正常环境变量。
     */
    private String normalizeUtf8Value(String value) {
        if (value.isEmpty()) return value;
        byte[] originalBytes = value.getBytes(StandardCharsets.ISO_8859_1);
        String decoded = new String(originalBytes, StandardCharsets.UTF_8);
        if (decoded.indexOf('\uFFFD') >= 0) return value;
        return countChinese(decoded) > countChinese(value) ? decoded : value;
    }

    private int countChinese(String value) {
        int count = 0;
        for (int offset = 0; offset < value.length();) {
            int codePoint = value.codePointAt(offset);
            Character.UnicodeScript script = Character.UnicodeScript.of(codePoint);
            if (script == Character.UnicodeScript.HAN) count++;
            offset += Character.charCount(codePoint);
        }
        return count;
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
