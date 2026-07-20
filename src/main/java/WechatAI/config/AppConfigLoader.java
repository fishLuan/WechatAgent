package WechatAI.config;

import WechatAI.AdvancedBotDemo;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

public class AppConfigLoader {

    private static final String CONFIG_FILE = "config.properties";
    private static final String DEFAULT_TEXT_API_URL = "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions";
    private static final String DEFAULT_TEXT_MODEL = "qwen3.7-max";
    private static final String DEFAULT_VISION_API_URL = "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions";
    private static final String DEFAULT_VISION_MODEL = "qwen3.5-ocr";
    private static final String DEFAULT_IMAGE_GENERATION_API_URL = "https://dashscope.aliyuncs.com/api/v1/services/aigc/multimodal-generation/generation";
    private static final String DEFAULT_IMAGE_GENERATION_MODEL = "qwen-image-plus";
    private static final String DEFAULT_SPEECH_RECOGNITION_API_URL = "https://dashscope.aliyuncs.com/api/v1/services/aigc/multimodal-generation/generation";
    private static final String DEFAULT_SPEECH_RECOGNITION_MODEL = "fun-asr-flash-2026-06-15";
    private static final String DEFAULT_SPEECH_SYNTHESIS_API_URL = "wss://${QWEN_WORKSPACE_ID}.cn-beijing.maas.aliyuncs.com/api-ws/v1/realtime?model=${qwen.speech.synthesis.model}";
    private static final String DEFAULT_SPEECH_SYNTHESIS_MODEL = "qwen-audio-3.0-tts-plus";
    private static final String DEFAULT_SPEECH_SYNTHESIS_VOICE = "Cherry";
    private static final String DEFAULT_REDIS_HOST = "127.0.0.1";
    private static final String DEFAULT_REDIS_PORT = "6379";
    private static final String DEFAULT_REDIS_PASSWORD = "";
    private static final String DEFAULT_REDIS_DATABASE = "0";
    private static final String DEFAULT_MEMORY_ENABLED = "true";
    private static final String DEFAULT_MEMORY_MAX_MESSAGES = "20";
    private static final String DEFAULT_MEMORY_TTL_SECONDS = "1800";
    private static final String DEFAULT_SYSTEM_PROMPT = "你是一个友好的微信AI助手，请用简洁、温暖的方式回答用户的问题。";
    private static final String PLACEHOLDER_API_KEY = "sk-your-actual-api-key-here";

    private AppConfigLoader() {
    }

    public static AiProperties load() {
        Properties props = new Properties();
        try (InputStream input = AdvancedBotDemo.class.getClassLoader().getResourceAsStream(CONFIG_FILE)) {
            if (input == null) {
                fail("❌ 找不到 config.properties 文件！\n请在 src/main/resources/ 目录下创建 config.properties");
            }

            props.load(new InputStreamReader(input, StandardCharsets.UTF_8));
            String commonApiKey = resolveValue(props.getProperty("qwen.api.key"), props);
            String workspaceId = resolveValue(props.getProperty("qwen.workspace.id", "${QWEN_WORKSPACE_ID}"), props);
            props.setProperty("qwen.workspace.id", workspaceId);
            AiProperties properties = new AiProperties(
                    commonApiKey,
                    resolveValue(props.getProperty("qwen.text.api.url", DEFAULT_TEXT_API_URL), props),
                    resolveValue(props.getProperty("qwen.text.model", DEFAULT_TEXT_MODEL), props),
                    resolveValue(props.getProperty("system.prompt", DEFAULT_SYSTEM_PROMPT), props),
                    commonApiKey,
                    resolveValue(props.getProperty("qwen.vision.api.url", DEFAULT_VISION_API_URL), props),
                    resolveValue(props.getProperty("qwen.vision.model", DEFAULT_VISION_MODEL), props),
                    commonApiKey,
                    resolveValue(props.getProperty("qwen.image.generation.api.url", DEFAULT_IMAGE_GENERATION_API_URL), props),
                    resolveValue(props.getProperty("qwen.image.generation.model", DEFAULT_IMAGE_GENERATION_MODEL), props),
                    commonApiKey,
                    resolveValue(props.getProperty("qwen.speech.recognition.api.url", DEFAULT_SPEECH_RECOGNITION_API_URL), props),
                    resolveValue(props.getProperty("qwen.speech.recognition.model", DEFAULT_SPEECH_RECOGNITION_MODEL), props),
                    commonApiKey,
                    resolveValue(props.getProperty("qwen.speech.synthesis.api.url", DEFAULT_SPEECH_SYNTHESIS_API_URL), props),
                    resolveValue(props.getProperty("qwen.speech.synthesis.model", DEFAULT_SPEECH_SYNTHESIS_MODEL), props),
                    resolveValue(props.getProperty("qwen.speech.synthesis.voice", DEFAULT_SPEECH_SYNTHESIS_VOICE), props),
                    resolveValue(props.getProperty("redis.host", DEFAULT_REDIS_HOST), props),
                    parseInt(resolveValue(props.getProperty("redis.port", DEFAULT_REDIS_PORT), props), 6379),
                    resolveValue(props.getProperty("redis.password", DEFAULT_REDIS_PASSWORD), props),
                    parseInt(resolveValue(props.getProperty("redis.database", DEFAULT_REDIS_DATABASE), props), 0),
                    Boolean.parseBoolean(resolveValue(props.getProperty("memory.enabled", DEFAULT_MEMORY_ENABLED), props)),
                    parseInt(resolveValue(props.getProperty("memory.max.messages", DEFAULT_MEMORY_MAX_MESSAGES), props), 20),
                    parseInt(resolveValue(props.getProperty("memory.ttl.seconds", DEFAULT_MEMORY_TTL_SECONDS), props), 1800)
            );

            validate(properties);
            printLoadedConfig(properties);
            return properties;
        } catch (IOException e) {
            fail("❌ 读取配置文件失败: " + e.getMessage());
            return null;
        }
    }

    private static void validate(AiProperties properties) {
        String apiKey = properties.getTextApiKey();
        if (apiKey == null || apiKey.isEmpty() || PLACEHOLDER_API_KEY.equals(apiKey) || isUnresolvedPlaceholder(apiKey)) {
            fail("❌ 请配置 qwen.api.key，或在 Windows 环境变量中设置 QWEN_API_KEY");
        }
        if (hasUnresolvedPlaceholder(properties.getSpeechSynthesisApiUrl())) {
            fail("❌ 请配置 qwen.workspace.id，或在 Windows 环境变量中设置 QWEN_WORKSPACE_ID，用于 qwen-audio-3.0-tts-plus 语音生成接口");
        }
    }

    private static boolean isUnresolvedPlaceholder(String value) {
        return value.startsWith("${") && value.endsWith("}");
    }

    private static boolean hasUnresolvedPlaceholder(String value) {
        return value != null && value.contains("${");
    }

    private static String resolveValue(String value, Properties props) {
        if (value == null) {
            return null;
        }

        String trimmed = value.trim();
        String resolved = value;
        int start = resolved.indexOf("${");
        while (start >= 0) {
            int end = resolved.indexOf("}", start);
            if (end < 0) {
                return resolved;
            }
            String key = resolved.substring(start + 2, end);
            String replacement = props.getProperty(key);
            if (replacement == null || replacement.equals("${" + key + "}")) {
                replacement = System.getenv(key);
            }
            if (replacement == null || replacement.isEmpty()) {
                return resolved;
            }
            resolved = resolved.substring(0, start) + replacement + resolved.substring(end + 1);
            start = resolved.indexOf("${");
        }
        return resolved;
    }

    private static void printLoadedConfig(AiProperties properties) {
        System.out.println("✅ 配置加载成功！");
        System.out.println("   API Key: " + maskApiKey(properties.getTextApiKey()));
        System.out.println("   Text API URL: " + properties.getTextApiUrl());
        System.out.println("   Text Model: " + properties.getTextModel());
        System.out.println("   Vision API URL: " + properties.getVisionApiUrl());
        System.out.println("   Vision Model: " + properties.getVisionModel());
        System.out.println("   Image Generation API URL: " + properties.getImageGenerationApiUrl());
        System.out.println("   Image Generation Model: " + properties.getImageGenerationModel());
        System.out.println("   Speech Recognition API URL: " + properties.getSpeechRecognitionApiUrl());
        System.out.println("   Speech Recognition Model: " + properties.getSpeechRecognitionModel());
        System.out.println("   Speech Synthesis API URL: " + properties.getSpeechSynthesisApiUrl());
        System.out.println("   Speech Synthesis Model: " + properties.getSpeechSynthesisModel());
        System.out.println("   Speech Synthesis Voice: " + properties.getSpeechSynthesisVoice());
        System.out.println("   Redis: " + properties.getRedisHost() + ":" + properties.getRedisPort()
                + "/" + properties.getRedisDatabase());
        System.out.println("   Memory Enabled: " + properties.isMemoryEnabled());
        System.out.println("   Memory Max Messages: " + properties.getMemoryMaxMessages());
        System.out.println("   Memory TTL Seconds: " + properties.getMemoryTtlSeconds());
    }

    private static int parseInt(String value, int defaultValue) {
        try {
            return Integer.parseInt(value);
        } catch (Exception e) {
            return defaultValue;
        }
    }

    private static String maskApiKey(String apiKey) {
        if (apiKey == null || apiKey.length() < 10) {
            return "未配置";
        }
        return apiKey.substring(0, 6) + "..." + apiKey.substring(apiKey.length() - 4);
    }

    private static void fail(String message) {
        System.err.println(message);
        System.exit(1);
    }
}
