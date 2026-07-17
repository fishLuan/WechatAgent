package WechatAI.config;

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
            String imageGenerationModel
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
}
