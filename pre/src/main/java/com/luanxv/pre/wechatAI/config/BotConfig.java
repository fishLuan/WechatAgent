package com.luanxv.pre.wechatAI.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** External DashScope configuration. The API key must come from the environment. */
@ConfigurationProperties(prefix = "qwen")
public class BotConfig {
    private String apiKey;
    private String chatUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions";
    private String imageUrl = "https://dashscope.aliyuncs.com/api/v1/services/aigc/multimodal-generation/generation";
    private String chatModel = "qwen-plus";
    private String vlModel = "qwen-vl-plus";
    private String imageModel = "wan2.7-image-pro";
    private boolean webSearchEnabled = true;
    private String generatedImageDirectory = "generated-images";
    private String speechRecognitionModel = "qwen3-asr-flash";
    private String speechSynthesisModel = "qwen3-tts-flash";
    private String speechVoice = "Cherry";
    /** Adult male voice. This voice belongs to the CosyVoice v3 model family. */
    private String speechMaleVoice = "longanyang";
    private String speechMaleModel = "cosyvoice-v3-flash";
    private String speechAudioMimeType = "audio/ogg";
    private String speechTtsUrl = "https://dashscope.aliyuncs.com/api/v1/services/audio/tts/SpeechSynthesizer";
    private String systemPrompt = "你是一个友好的微信 AI 助手，请用简洁、温暖的方式回答用户的问题。";

    public String getQwenApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }
    public String getQwenChatUrl() { return chatUrl; }
    public void setChatUrl(String chatUrl) { this.chatUrl = chatUrl; }
    public String getQwenImageUrl() { return imageUrl; }
    public void setImageUrl(String imageUrl) { this.imageUrl = imageUrl; }
    public String getQwenChatModel() { return chatModel; }
    public void setChatModel(String chatModel) { this.chatModel = chatModel; }
    public String getQwenVlModel() { return vlModel; }
    public void setVlModel(String vlModel) { this.vlModel = vlModel; }
    public String getQwenImageModel() { return imageModel; }
    public void setImageModel(String imageModel) { this.imageModel = imageModel; }
    public boolean isWebSearchEnabled() { return webSearchEnabled; }
    public void setWebSearchEnabled(boolean webSearchEnabled) { this.webSearchEnabled = webSearchEnabled; }
    public String getGeneratedImageDirectory() { return generatedImageDirectory; }
    public void setGeneratedImageDirectory(String generatedImageDirectory) { this.generatedImageDirectory = generatedImageDirectory; }
    public String getSpeechRecognitionModel() { return speechRecognitionModel; }
    public void setSpeechRecognitionModel(String speechRecognitionModel) { this.speechRecognitionModel = speechRecognitionModel; }
    public String getSpeechSynthesisModel() { return speechSynthesisModel; }
    public void setSpeechSynthesisModel(String speechSynthesisModel) { this.speechSynthesisModel = speechSynthesisModel; }
    public String getSpeechVoice() { return speechVoice; }
    public void setSpeechVoice(String speechVoice) { this.speechVoice = speechVoice; }
    public String getSpeechMaleVoice() { return speechMaleVoice; }
    public void setSpeechMaleVoice(String speechMaleVoice) { this.speechMaleVoice = speechMaleVoice; }
    public String getSpeechMaleModel() { return speechMaleModel; }
    public void setSpeechMaleModel(String speechMaleModel) { this.speechMaleModel = speechMaleModel; }
    public String getSpeechAudioMimeType() { return speechAudioMimeType; }
    public void setSpeechAudioMimeType(String speechAudioMimeType) { this.speechAudioMimeType = speechAudioMimeType; }
    public String getSpeechTtsUrl() { return speechTtsUrl; }
    public void setSpeechTtsUrl(String speechTtsUrl) { this.speechTtsUrl = speechTtsUrl; }
    public String getSystemPrompt() { return systemPrompt; }
    public void setSystemPrompt(String systemPrompt) { this.systemPrompt = systemPrompt; }
}
