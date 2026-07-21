package com.luanxv.pre.wechatAI.service.impl;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.luanxv.pre.wechatAI.config.BotConfig;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.Base64;
import java.util.concurrent.TimeUnit;

@Service
public class QwenSpeechService {
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    private final BotConfig config;
    private final OkHttpClient httpClient;
    private final Gson gson = new Gson();

    public QwenSpeechService(BotConfig config) {
        this.config = config;
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(60, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .writeTimeout(120, TimeUnit.SECONDS)
                .build();
    }

    public String recognizeSpeech(byte[] audioBytes) {
        try {
            JsonObject body = new JsonObject();
            body.addProperty("model", config.getSpeechRecognitionModel());

            JsonObject input = new JsonObject();
            JsonArray messages = new JsonArray();
            JsonObject userMessage = new JsonObject();
            userMessage.addProperty("role", "user");
            JsonArray content = new JsonArray();
            JsonObject audio = new JsonObject();
            String dataUrl = "data:" + config.getSpeechAudioMimeType() + ";base64,"
                    + Base64.getEncoder().encodeToString(audioBytes);
            audio.addProperty("audio", dataUrl);
            content.add(audio);
            userMessage.add("content", content);
            messages.add(userMessage);
            input.add("messages", messages);
            body.add("input", input);

            JsonObject parameters = new JsonObject();
            JsonObject asrOptions = new JsonObject();
            asrOptions.addProperty("language", "zh");
            asrOptions.addProperty("enable_itn", true);
            parameters.add("asr_options", asrOptions);
            body.add("parameters", parameters);

            try (Response response = execute(body, config.getQwenImageUrl())) {
                if (!response.isSuccessful()) {
                    System.err.println("语音识别请求失败: " + response.code());
                    return null;
                }
                return extractText(gson.fromJson(response.body().string(), JsonObject.class));
            }
        } catch (Exception exception) {
            System.err.println("语音识别失败: " + exception.getMessage());
            return null;
        }
    }

    public byte[] synthesizeSpeech(String text, String voice, String model) {
        try {
            JsonObject body = new JsonObject();
            String selectedModel = model == null || model.isBlank() ? config.getSpeechSynthesisModel() : model;
            String selectedVoice = voice == null || voice.isBlank() ? config.getSpeechVoice() : voice;
            System.out.printf("[tts] model=%s, voice=%s%n", selectedModel, selectedVoice);
            body.addProperty("model", selectedModel);
            JsonObject input = new JsonObject();
            input.addProperty("text", text);
            input.addProperty("voice", selectedVoice);
            input.addProperty("format", "mp3");
            input.addProperty("sample_rate", 24000);
            body.add("input", input);

            try (Response response = execute(body, config.getSpeechTtsUrl())) {
                if (!response.isSuccessful()) {
                    System.err.println("语音生成请求失败: " + response.code() + ", " + response.body().string());
                    return null;
                }
                JsonObject result = gson.fromJson(response.body().string(), JsonObject.class);
                String audioUrl = findAudioUrl(result);
                return audioUrl == null ? null : downloadAudio(audioUrl);
            }
        } catch (Exception exception) {
            System.err.println("语音生成失败: " + exception.getMessage());
            return null;
        }
    }

    private Response execute(JsonObject body, String endpoint) throws IOException {
        Request request = new Request.Builder()
                .url(endpoint)
                .addHeader("Authorization", "Bearer " + config.getQwenApiKey())
                .addHeader("Content-Type", "application/json")
                .post(RequestBody.create(JSON, gson.toJson(body)))
                .build();
        return httpClient.newCall(request).execute();
    }

    private byte[] downloadAudio(String audioUrl) throws IOException {
        Request request = new Request.Builder().url(audioUrl).get().build();
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("下载语音失败: " + response.code());
            }
            return response.body().bytes();
        }
    }

    private String extractText(JsonObject response) {
        JsonObject output = response.getAsJsonObject("output");
        if (output != null && output.has("text")) {
            return output.get("text").getAsString();
        }
        JsonArray choices = output == null ? null : output.getAsJsonArray("choices");
        if (choices == null || choices.isEmpty()) {
            return null;
        }
        JsonObject message = choices.get(0).getAsJsonObject().getAsJsonObject("message");
        if (message == null || !message.has("content")) {
            return null;
        }
        JsonElement content = message.get("content");
        if (content.isJsonPrimitive()) {
            return content.getAsString();
        }
        for (JsonElement item : content.getAsJsonArray()) {
            JsonObject value = item.getAsJsonObject();
            if (value.has("text")) {
                return value.get("text").getAsString();
            }
        }
        return null;
    }

    private String findAudioUrl(JsonObject response) {
        JsonObject output = response.getAsJsonObject("output");
        if (output == null) {
            return null;
        }
        JsonObject audio = output.getAsJsonObject("audio");
        if (audio != null && audio.has("url")) {
            return audio.get("url").getAsString();
        }
        return output.has("audio_url") ? output.get("audio_url").getAsString() : null;
    }
}
