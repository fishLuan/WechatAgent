package com.luanxv.pre.wechatAI.service.impl;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import com.luanxv.pre.wechatAI.config.BotConfig;
import com.luanxv.pre.wechatAI.core.ConversationMemoryService;

import java.io.IOException;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Service;

/** Handles a single visual question while retaining textual conversation context. */
@Service
public class QwenVLService {
    private final BotConfig config;
    private final ConversationMemoryService memoryService;
    private final OkHttpClient httpClient;
    private final Gson gson;

    public QwenVLService(BotConfig config, ConversationMemoryService memoryService) {
        this.config = config;
        this.memoryService = memoryService;
        this.gson = new Gson();
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(60, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .writeTimeout(120, TimeUnit.SECONDS)
                .build();
    }

    public String recognizeImage(String userId, byte[] imageBytes, String userQuestion) {
        try {
            String base64Image = Base64.getEncoder().encodeToString(imageBytes);
            String dataUrl = "data:image/png;base64," + base64Image;
            List<Map<String, String>> history = memoryService.getHistory(userId);
            JsonObject requestBody = buildRequest(dataUrl, userQuestion, history);
            Request request = buildRequest(config.getQwenChatUrl(), requestBody);

            try (Response response = httpClient.newCall(request).execute()) {
                return parseResponse(response);
            }
        } catch (Exception e) {
            System.err.println("Qwen-VL call failed: " + e.getMessage());
            return null;
        }
    }

    private JsonObject buildRequest(String dataUrl, String userQuestion,
                                    List<Map<String, String>> history) {
        JsonObject requestBody = new JsonObject();
        requestBody.addProperty("model", config.getQwenVlModel());

        JsonArray messages = new JsonArray();
        JsonObject systemMessage = new JsonObject();
        systemMessage.addProperty("role", "system");
        systemMessage.addProperty("content", config.getSystemPrompt());
        systemMessage.addProperty(
                "content",
                config.getSystemPrompt()
                        + " 图片识别回复必须简洁：直接说结论，最多150个汉字；"
                        + "除非用户明确要求，不要展开描述、不要分析过程。"
        );
        messages.add(systemMessage);

        // Keep text summaries of previous images and turns, not their Base64 data.
        for (Map<String, String> historyItem : history) {
            JsonObject historyMessage = new JsonObject();
            historyMessage.addProperty("role", historyItem.get("role"));
            historyMessage.addProperty("content", historyItem.get("content"));
            messages.add(historyMessage);
        }

        JsonArray contentArray = new JsonArray();
        JsonObject imageContent = new JsonObject();
        imageContent.addProperty("type", "image_url");
        imageContent.addProperty("image_url", dataUrl);
        contentArray.add(imageContent);

        JsonObject textContent = new JsonObject();
        textContent.addProperty("type", "text");
        String prompt = (userQuestion == null || userQuestion.trim().isEmpty())
                ? "Please describe this image."
                : userQuestion;
        textContent.addProperty("text", prompt);
        contentArray.add(textContent);

        JsonObject userMessage = new JsonObject();
        userMessage.addProperty("role", "user");
        userMessage.add("content", contentArray);
        messages.add(userMessage);
        requestBody.add("messages", messages);requestBody.addProperty("max_tokens", 80);
        requestBody.addProperty("temperature", 0.2);
        return requestBody;
    }

    private Request buildRequest(String url, JsonObject body) {
        return new Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer " + config.getQwenApiKey())
                .addHeader("Content-Type", "application/json")
                .post(RequestBody.create(
                        MediaType.parse("application/json; charset=utf-8"),
                        gson.toJson(body)))
                .build();
    }

    private String parseResponse(Response response) throws IOException {
        if (!response.isSuccessful()) {
            System.err.println("API request failed: " + response.code());
            return null;
        }

        String responseBody = response.body().string();
        JsonObject jsonResponse = gson.fromJson(responseBody, JsonObject.class);
        JsonArray choices = jsonResponse.getAsJsonArray("choices");
        if (choices != null && choices.size() > 0) {
            JsonObject firstChoice = choices.get(0).getAsJsonObject();
            JsonObject message = firstChoice.getAsJsonObject("message");
            if (message != null && message.has("content")) {
                return message.get("content").getAsString();
            }
        }
        return null;
    }
}
