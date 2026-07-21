package com.xwc.demo.llm;

import java.io.FileReader;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Properties;

public class LlmConfig {

    public String textBaseUrl;
    public String textApiKey;
    public String textModel;

    public String visionBaseUrl;
    public String visionApiKey;
    public String visionModel;

    public boolean imageGenEnabled;
    public String imageGenBaseUrl;
    public String imageGenApiKey;
    public String imageGenModel;
    public String imageSize = "1024*1024";
    public int imageN = 1;

    // ==================== TTS 语音生成配置 ====================
    public String ttsBaseUrl;
    public String ttsApiKey;
    public String ttsModel;
    public String ttsVoice;

    public static LlmConfig load() {
        LlmConfig cfg = new LlmConfig();
        try {
            Properties props = new Properties();
            if (Files.exists(Paths.get("config.properties"))) {
                try (FileReader fr = new FileReader("config.properties")) { props.load(fr); }
            } else {
                InputStream is = LlmConfig.class.getClassLoader().getResourceAsStream("config.properties");
                if (is != null) { props.load(is); is.close(); }
            }

            cfg.textBaseUrl = trim(props.getProperty("llm.base-url", ""));
            cfg.textApiKey  = trim(props.getProperty("llm.api-key", ""));
            cfg.textModel   = trim(props.getProperty("llm.model", ""));

            cfg.visionBaseUrl = trim(props.getProperty("vision.base-url", cfg.textBaseUrl));
            cfg.visionApiKey  = trim(props.getProperty("vision.api-key", cfg.textApiKey));
            cfg.visionModel   = trim(props.getProperty("vision.model", "qwen-vl-max"));

            cfg.imageGenEnabled = "true".equalsIgnoreCase(props.getProperty("imagegen.enabled", "false"));
            cfg.imageGenBaseUrl = trim(props.getProperty("imagegen.base-url", ""));
            cfg.imageGenApiKey  = trim(props.getProperty("imagegen.api-key", cfg.textApiKey));
            cfg.imageGenModel   = trim(props.getProperty("imagegen.model", ""));
            cfg.imageSize       = trim(props.getProperty("imagegen.size", "1024*1024"));
            cfg.imageN          = Integer.parseInt(props.getProperty("imagegen.n", "1"));

            // TTS 配置
            cfg.ttsBaseUrl = trim(props.getProperty("tts.base-url", ""));
            if (cfg.ttsBaseUrl.isEmpty()) {
                String derived = VoiceGeneration.deriveTtsUrl(cfg.textBaseUrl);
                if (derived != null) cfg.ttsBaseUrl = derived;
            }
            cfg.ttsApiKey = trim(props.getProperty("tts.api-key", ""));
            if (cfg.ttsApiKey.isEmpty()) cfg.ttsApiKey = cfg.imageGenApiKey;
            if (cfg.ttsApiKey.isEmpty()) cfg.ttsApiKey = cfg.textApiKey;
            cfg.ttsModel = trim(props.getProperty("tts.model", "qwen3-tts-flash"));
            cfg.ttsVoice = trim(props.getProperty("tts.voice", "Cherry"));
        } catch (Exception e) {
            System.err.println("[LlmConfig] 加载失败: " + e.getMessage());
        }
        return cfg;
    }

    public boolean isTextEnabled() {
        return !textBaseUrl.isEmpty() && !textApiKey.isEmpty() && !textModel.isEmpty();
    }

    public boolean isVisionEnabled() {
        return !visionBaseUrl.isEmpty() && !visionApiKey.isEmpty() && !visionModel.isEmpty();
    }

    public boolean isImageGenEnabled() {
        return imageGenEnabled && !imageGenBaseUrl.isEmpty() && !imageGenApiKey.isEmpty() && !imageGenModel.isEmpty();
    }

    public boolean isTtsEnabled() {
        return !ttsApiKey.isEmpty() && !ttsModel.isEmpty();
    }

    private static String trim(String s) {
        return s == null ? "" : s.trim();
    }
}