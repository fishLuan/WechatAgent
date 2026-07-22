package WechatAI.config;

/**
 * 应用配置快照，启动时由 config.properties 和环境变量解析生成。
 */
public class AiProperties {

    private final String textApiKey;
    private final String textApiUrl;
    private final String textModel;
    private final String systemPrompt;
    private final String visionApiKey;
    private final String visionApiUrl;
    private final String visionModel;
    private final String imageGenerationApiKey;
    private final String imageGenerationApiUrl;
    private final String imageGenerationModel;
    private final String speechRecognitionApiKey;
    private final String speechRecognitionApiUrl;
    private final String speechRecognitionModel;
    private final String speechSynthesisApiKey;
    private final String speechSynthesisApiUrl;
    private final String speechSynthesisModel;
    private final String speechSynthesisVoice;
    private final String redisHost;
    private final int redisPort;
    private final String redisPassword;
    private final int redisDatabase;
    private final boolean memoryEnabled;
    private final int memoryMaxMessages;
    private final int memoryTtlSeconds;

    public AiProperties(
            String textApiKey,
            String textApiUrl,
            String textModel,
            String systemPrompt,
            String visionApiKey,
            String visionApiUrl,
            String visionModel,
            String imageGenerationApiKey,
            String imageGenerationApiUrl,
            String imageGenerationModel,
            String speechRecognitionApiKey,
            String speechRecognitionApiUrl,
            String speechRecognitionModel,
            String speechSynthesisApiKey,
            String speechSynthesisApiUrl,
            String speechSynthesisModel,
            String speechSynthesisVoice,
            String redisHost,
            int redisPort,
            String redisPassword,
            int redisDatabase,
            boolean memoryEnabled,
            int memoryMaxMessages,
            int memoryTtlSeconds
    ) {
        this.textApiKey = textApiKey;
        this.textApiUrl = textApiUrl;
        this.textModel = textModel;
        this.systemPrompt = systemPrompt;
        this.visionApiKey = visionApiKey;
        this.visionApiUrl = visionApiUrl;
        this.visionModel = visionModel;
        this.imageGenerationApiKey = imageGenerationApiKey;
        this.imageGenerationApiUrl = imageGenerationApiUrl;
        this.imageGenerationModel = imageGenerationModel;
        this.speechRecognitionApiKey = speechRecognitionApiKey;
        this.speechRecognitionApiUrl = speechRecognitionApiUrl;
        this.speechRecognitionModel = speechRecognitionModel;
        this.speechSynthesisApiKey = speechSynthesisApiKey;
        this.speechSynthesisApiUrl = speechSynthesisApiUrl;
        this.speechSynthesisModel = speechSynthesisModel;
        this.speechSynthesisVoice = speechSynthesisVoice;
        this.redisHost = redisHost;
        this.redisPort = redisPort;
        this.redisPassword = redisPassword;
        this.redisDatabase = redisDatabase;
        this.memoryEnabled = memoryEnabled;
        this.memoryMaxMessages = memoryMaxMessages;
        this.memoryTtlSeconds = memoryTtlSeconds;
    }

    public String getTextApiKey() {
        return textApiKey;
    }

    public String getTextApiUrl() {
        return textApiUrl;
    }

    public String getTextModel() {
        return textModel;
    }

    public String getSystemPrompt() {
        return systemPrompt;
    }

    public String getVisionApiKey() {
        return visionApiKey;
    }

    public String getVisionApiUrl() {
        return visionApiUrl;
    }

    public String getVisionModel() {
        return visionModel;
    }

    public String getImageGenerationApiKey() {
        return imageGenerationApiKey;
    }

    public String getImageGenerationApiUrl() {
        return imageGenerationApiUrl;
    }

    public String getImageGenerationModel() {
        return imageGenerationModel;
    }

    public String getSpeechRecognitionApiKey() {
        return speechRecognitionApiKey;
    }

    public String getSpeechRecognitionApiUrl() {
        return speechRecognitionApiUrl;
    }

    public String getSpeechRecognitionModel() {
        return speechRecognitionModel;
    }

    public String getSpeechSynthesisApiKey() {
        return speechSynthesisApiKey;
    }

    public String getSpeechSynthesisApiUrl() {
        return speechSynthesisApiUrl;
    }

    public String getSpeechSynthesisModel() {
        return speechSynthesisModel;
    }

    public String getSpeechSynthesisVoice() {
        return speechSynthesisVoice;
    }

    public String getRedisHost() {
        return redisHost;
    }

    public int getRedisPort() {
        return redisPort;
    }

    public String getRedisPassword() {
        return redisPassword;
    }

    public int getRedisDatabase() {
        return redisDatabase;
    }

    public boolean isMemoryEnabled() {
        return memoryEnabled;
    }

    public int getMemoryMaxMessages() {
        return memoryMaxMessages;
    }

    public int getMemoryTtlSeconds() {
        return memoryTtlSeconds;
    }
}
