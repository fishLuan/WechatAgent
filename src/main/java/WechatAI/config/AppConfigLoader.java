package WechatAI.config;

import WechatAI.AdvancedBotDemo;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

public class AppConfigLoader {

    private static final String CONFIG_FILE = "config.properties";
    private static final String DEFAULT_TEXT_API_URL = "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions";
    private static final String DEFAULT_TEXT_MODEL = "qwen3.7-max";
    private static final String DEFAULT_VISION_API_URL = "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions";
    private static final String DEFAULT_VISION_MODEL = "qwen3.5-ocr";
    private static final String DEFAULT_IMAGE_GENERATION_API_URL = "https://dashscope.aliyuncs.com/api/v1/services/aigc/multimodal-generation/generation";
    private static final String DEFAULT_IMAGE_GENERATION_MODEL = "qwen-image-plus";
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

            props.load(input);
            String commonApiKey = resolveValue(props.getProperty("qwen.api.key"), props);
            AiProperties properties = new AiProperties(
                    commonApiKey,
                    props.getProperty("qwen.text.api.url", DEFAULT_TEXT_API_URL),
                    props.getProperty("qwen.text.model", DEFAULT_TEXT_MODEL),
                    props.getProperty("system.prompt", DEFAULT_SYSTEM_PROMPT),
                    commonApiKey,
                    props.getProperty("qwen.vision.api.url", DEFAULT_VISION_API_URL),
                    props.getProperty("qwen.vision.model", DEFAULT_VISION_MODEL),
                    commonApiKey,
                    props.getProperty("qwen.image.generation.api.url", DEFAULT_IMAGE_GENERATION_API_URL),
                    props.getProperty("qwen.image.generation.model", DEFAULT_IMAGE_GENERATION_MODEL)
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
    }

    private static boolean isUnresolvedPlaceholder(String value) {
        return value.startsWith("${") && value.endsWith("}");
    }

    private static String resolveValue(String value, Properties props) {
        if (value == null) {
            return null;
        }

        String trimmed = value.trim();
        if (!trimmed.startsWith("${") || !trimmed.endsWith("}")) {
            return value;
        }

        String key = trimmed.substring(2, trimmed.length() - 1);
        String propertyValue = props.getProperty(key);
        if (propertyValue != null && !propertyValue.equals(value)) {
            return resolveValue(propertyValue, props);
        }

        String envValue = System.getenv(key);
        if (envValue != null && !envValue.isEmpty()) {
            return envValue;
        }
        return value;
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
