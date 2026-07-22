package WechatAI.service.impl;

import WechatAI.config.AiProperties;
import WechatAI.model.VoiceReply;
import WechatAI.service.SpeechSynthesisService;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import java.util.concurrent.TimeUnit;

/**
 * 千问语音合成实现，负责按文本和音色生成可发送的音频。
 */
public class QwenSpeechSynthesisService implements SpeechSynthesisService {

    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    private final AiProperties properties;
    private final OkHttpClient httpClient;
    private final Gson gson;
    private String lastErrorMessage;

    public QwenSpeechSynthesisService(AiProperties properties) {
        this(properties, defaultHttpClient(), new Gson());
    }

    public QwenSpeechSynthesisService(AiProperties properties, OkHttpClient httpClient, Gson gson) {
        this.properties = properties;
        this.httpClient = httpClient;
        this.gson = gson;
    }

    @Override
    public VoiceReply synthesize(String text) {
        return synthesize(text, properties.getSpeechSynthesisVoice());
    }

    @Override
    public VoiceReply synthesize(String text, String voice) {
        lastErrorMessage = null;
        if (!hasApiKey(properties.getSpeechSynthesisApiKey())) {
            setLastErrorMessage("语音生成模型还没有配置 API Key。");
            return null;
        }

        String normalizedText = sanitizeText(text);
        if (normalizedText.isEmpty()) {
            setLastErrorMessage("语音生成文本为空。");
            return null;
        }

        try {
            String selectedVoice = hasText(voice) ? voice.trim() : properties.getSpeechSynthesisVoice();
            System.out.println("🔊 使用音色: " + selectedVoice);
            String audioUrl = createAudioUrl(normalizedText, selectedVoice);
            if (audioUrl == null || audioUrl.isEmpty()) {
                if (lastErrorMessage == null || lastErrorMessage.isEmpty()) {
                    setLastErrorMessage("语音生成没有返回音频下载地址。");
                }
                return null;
            }

            DownloadedAudio downloadedAudio = downloadAudio(audioUrl);
            if (downloadedAudio.bytes == null || downloadedAudio.bytes.length == 0) {
                setLastErrorMessage("语音文件下载后为空。");
                return null;
            }

            System.out.println("🔊 语音文件下载完成: " + downloadedAudio.bytes.length + " bytes");
            return new VoiceReply(downloadedAudio.bytes, downloadedAudio.fileName, downloadedAudio.mimeType);
        } catch (Exception e) {
            setLastErrorMessage("语音生成请求失败：" + e.getMessage());
            return null;
        }
    }

    @Override
    public String getLastErrorMessage() {
        return lastErrorMessage;
    }

    private String createAudioUrl(String text, String voice) throws Exception {
        Request request = new Request.Builder()
                .url(properties.getSpeechSynthesisApiUrl())
                .addHeader("Authorization", "Bearer " + properties.getSpeechSynthesisApiKey())
                .addHeader("Content-Type", "application/json")
                .post(RequestBody.create(gson.toJson(buildRequestBody(text, voice)), JSON))
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            String responseBody = response.body() == null ? "" : response.body().string();
            if (!response.isSuccessful()) {
                setLastErrorMessage("语音生成HTTP失败：" + response.code() + "，响应：" + responseBody);
                return null;
            }

            JsonObject json = gson.fromJson(responseBody, JsonObject.class);
            String audioUrl = findAudioUrl(json);
            if (audioUrl == null || audioUrl.isEmpty()) {
                setLastErrorMessage("语音生成响应中没有音频地址：" + responseBody);
                return null;
            }
            System.out.println("🔊 语音生成完成，开始下载音频文件");
            return audioUrl;
        }
    }

    private JsonObject buildRequestBody(String text, String voice) {
        JsonObject input = new JsonObject();
        input.addProperty("text", text);
        input.addProperty("voice", voice);

        JsonObject parameters = new JsonObject();
        parameters.addProperty("format", "mp3");
        parameters.addProperty("audio_format", "mp3");
        parameters.addProperty("response_format", "mp3");

        JsonObject body = new JsonObject();
        body.addProperty("model", properties.getSpeechSynthesisModel());
        body.add("input", input);
        body.add("parameters", parameters);
        return body;
    }

    private String findAudioUrl(JsonObject json) {
        String url = getString(json, "url");
        if (!url.isEmpty()) {
            return url;
        }
        if (json.has("output") && json.get("output").isJsonObject()) {
            JsonObject output = json.getAsJsonObject("output");
            url = getString(output, "url");
            if (!url.isEmpty()) {
                return url;
            }
            if (output.has("audio") && output.get("audio").isJsonObject()) {
                return getString(output.getAsJsonObject("audio"), "url");
            }
        }
        return "";
    }

    private DownloadedAudio downloadAudio(String audioUrl) throws Exception {
        Request request = new Request.Builder().url(audioUrl).get().build();
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                throw new IllegalStateException("下载音频失败：" + response.code());
            }
            String contentType = response.header("Content-Type", "audio/mpeg");
            byte[] bytes = response.body().bytes();
            return new DownloadedAudio(bytes, guessFileName(contentType), contentType);
        }
    }

    private String guessFileName(String contentType) {
        String normalized = contentType == null ? "" : contentType.toLowerCase();
        if (normalized.contains("wav")) {
            return "qwen-tts.wav";
        }
        if (normalized.contains("mpeg") || normalized.contains("mp3")) {
            return "qwen-tts.mp3";
        }
        return "qwen-tts.mp3";
    }

    private String getString(JsonObject object, String memberName) {
        if (object == null || !object.has(memberName) || object.get(memberName).isJsonNull()) {
            return "";
        }
        return object.get(memberName).getAsString();
    }

    private String sanitizeText(String text) {
        if (text == null) {
            return "";
        }
        String cleaned = text.replaceAll("[\\p{So}\\p{Cn}]", "").trim();
        if (cleaned.length() > 500) {
            return cleaned.substring(0, 500);
        }
        return cleaned;
    }

    private void setLastErrorMessage(String message) {
        lastErrorMessage = message;
        System.err.println("❌ " + message);
    }

    private static boolean hasApiKey(String apiKey) {
        return apiKey != null && !apiKey.trim().isEmpty() && !"sk-your-actual-api-key-here".equals(apiKey);
    }

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private static OkHttpClient defaultHttpClient() {
        return new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .writeTimeout(120, TimeUnit.SECONDS)
                .build();
    }

    private static class DownloadedAudio {
        private final byte[] bytes;
        private final String fileName;
        private final String mimeType;

        private DownloadedAudio(byte[] bytes, String fileName, String mimeType) {
            this.bytes = bytes;
            this.fileName = fileName;
            this.mimeType = mimeType;
        }
    }
}
