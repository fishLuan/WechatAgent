package com.luanxv.pre.wechatAI.service.impl;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.luanxv.pre.wechatAI.config.BotConfig;
import com.luanxv.pre.wechatAI.core.ConversationMemoryService;
import com.luanxv.pre.wechatAI.util.TextUtils;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Service
public class QwenChatService {
    private final BotConfig config;
    private final ConversationMemoryService memoryService;
    private final OkHttpClient httpClient;
    private final Gson gson = new Gson();

    public QwenChatService(BotConfig config, ConversationMemoryService memoryService) {
        this.config = config;
        this.memoryService = memoryService;
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(60, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .writeTimeout(120, TimeUnit.SECONDS)
                .build();
    }

    public String chat(String userId, String userMessage) {
        return chat(userId, userMessage, userMessage, false);
    }

    public String chat(String userId, String userMessage, boolean forceWebSearch) {
        return chat(userId, userMessage, userMessage, forceWebSearch);
    }

    public String chatDocument(String userId, String documentPrompt, String memorySummary) {
        return chat(userId, documentPrompt, memorySummary, false);
    }

    private String chat(String userId, String userMessage, String memorySummary, boolean forceWebSearch) {
        try {
            List<Map<String, String>> history = memoryService.getHistory(userId);
            Request request = buildRequest(buildChatRequest(userMessage, history, forceWebSearch));
            try (Response response = httpClient.newCall(request).execute()) {
                String reply = parseResponse(response);
                if (reply != null && !reply.isBlank()) {
                    memoryService.addMessage(userId, "user", memorySummary);
                    memoryService.addMessage(userId, "assistant", reply);
                }
                return reply;
            }
        } catch (Exception exception) {
            System.err.println("Qwen Chat 调用失败: " + exception.getMessage());
            return null;
        }
    }

    private JsonObject buildChatRequest(String userMessage, List<Map<String, String>> history, boolean forceWebSearch) {
        JsonObject body = new JsonObject();
        body.addProperty("model", config.getQwenChatModel());
        boolean useWebSearch = config.isWebSearchEnabled() && (forceWebSearch || TextUtils.isWebSearchRequest(userMessage));
        if (useWebSearch) {
            body.addProperty("enable_search", true);
            JsonObject searchOptions = new JsonObject();
            searchOptions.addProperty("forced_search", true);
            body.add("search_options", searchOptions);
            System.out.println("[web-search] enabled for current question");
        }
        JsonArray messages = new JsonArray();

        JsonObject system = new JsonObject();
        system.addProperty("role", "system");
        system.addProperty("content", config.getSystemPrompt());
        messages.add(system);

        for (Map<String, String> turn : history) {
            JsonObject historyMessage = new JsonObject();
            historyMessage.addProperty("role", turn.get("role"));
            historyMessage.addProperty("content", turn.get("content"));
            messages.add(historyMessage);
        }

        JsonObject user = new JsonObject();
        user.addProperty("role", "user");
        user.addProperty("content", userMessage);
        messages.add(user);
        body.add("messages", messages);
        body.addProperty("max_tokens", 80);
        body.addProperty("temperature", 0.7);
        return body;
    }

    private Request buildRequest(JsonObject body) {
        return new Request.Builder()
                .url(config.getQwenChatUrl())
                .addHeader("Authorization", "Bearer " + config.getQwenApiKey())
                .addHeader("Content-Type", "application/json")
                .post(RequestBody.create(MediaType.parse("application/json; charset=utf-8"), gson.toJson(body)))
                .build();
    }

    private String parseResponse(Response response) throws IOException {
        String responseBody = response.body() == null ? "" : response.body().string();
        if (!response.isSuccessful()) {
            System.err.println("Qwen Chat 请求失败: " + response.code() + ", " + responseBody);
            return null;
        }
        JsonObject root = gson.fromJson(responseBody, JsonObject.class);
        JsonArray choices = root == null ? null : root.getAsJsonArray("choices");
        if (choices == null || choices.isEmpty()) {
            return null;
        }
        JsonObject message = choices.get(0).getAsJsonObject().getAsJsonObject("message");
        return message != null && message.has("content") ? message.get("content").getAsString() : null;
    }
}
