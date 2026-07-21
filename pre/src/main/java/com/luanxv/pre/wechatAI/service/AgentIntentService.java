package com.luanxv.pre.wechatAI.service;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.luanxv.pre.wechatAI.config.BotConfig;
import com.luanxv.pre.wechatAI.model.AgentIntent;
import com.luanxv.pre.wechatAI.model.AgentTool;
import com.luanxv.pre.wechatAI.util.TextUtils;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/** Uses the LLM only to choose from the local, allow-listed tool set. */
@Service
public class AgentIntentService {
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
    private static final String ROUTER_PROMPT = """
            You are an intent router for a WeChat AI assistant. Return JSON only, with no markdown.
            Schema: {\"tool\":\"CHAT|WEB_SEARCH|IMAGE_GENERATE|IMAGE_EDIT\",\"argument\":\"...\"}.
            Select IMAGE_GENERATE when the user wants a new image created.
            Select IMAGE_EDIT only when the user asks to change a previously sent or generated image.
            Select WEB_SEARCH for current facts such as weather, news, prices, schedules, or when web search is requested.
            Select CHAT for every other request. argument must retain the user's actual request, without inventing facts.
            """;

    private final BotConfig config;
    private final OkHttpClient client;
    private final Gson gson = new Gson();

    public AgentIntentService(BotConfig config) {
        this.config = config;
        this.client = new OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS).writeTimeout(30, TimeUnit.SECONDS).build();
    }

    public AgentIntent route(String message) {
        AgentIntent fallback = fallback(message);
        try {
            JsonObject body = new JsonObject();
            body.addProperty("model", config.getQwenChatModel());
            body.addProperty("temperature", 0);
            body.addProperty("max_tokens", 120);
            var messages = new com.google.gson.JsonArray();
            messages.add(message("system", ROUTER_PROMPT));
            messages.add(message("user", message));
            body.add("messages", messages);

            Request request = new Request.Builder().url(config.getQwenChatUrl())
                    .addHeader("Authorization", "Bearer " + config.getQwenApiKey())
                    .addHeader("Content-Type", "application/json")
                    .post(RequestBody.create(JSON, gson.toJson(body))).build();
            try (Response response = client.newCall(request).execute()) {
                if (!response.isSuccessful() || response.body() == null) {
                    return fallback;
                }
                JsonObject root = gson.fromJson(response.body().string(), JsonObject.class);
                String content = root.getAsJsonArray("choices").get(0).getAsJsonObject()
                        .getAsJsonObject("message").get("content").getAsString();
                return parse(content, message, fallback);
            }
        } catch (Exception exception) {
            System.err.println("Agent 路由失败，已使用规则回退: " + exception.getMessage());
            return fallback;
        }
    }

    private JsonObject message(String role, String content) {
        JsonObject item = new JsonObject();
        item.addProperty("role", role);
        item.addProperty("content", content);
        return item;
    }

    private AgentIntent parse(String value, String original, AgentIntent fallback) {
        try {
            String cleaned = value.trim().replaceAll("^```(?:json)?|```$", "").trim();
            JsonObject json = gson.fromJson(cleaned, JsonObject.class);
            AgentTool tool = AgentTool.valueOf(json.get("tool").getAsString().toUpperCase(Locale.ROOT));
            String argument = json.has("argument") ? json.get("argument").getAsString().trim() : original;
            return new AgentIntent(tool, argument.isBlank() ? original : argument);
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private AgentIntent fallback(String message) {
        if (TextUtils.isImageGenerationRequest(message)) {
            return new AgentIntent(AgentTool.IMAGE_GENERATE, TextUtils.cleanImagePrompt(message));
        }
        if (TextUtils.isImageEditInstruction(message)) {
            return new AgentIntent(AgentTool.IMAGE_EDIT, message);
        }
        return new AgentIntent(TextUtils.isWebSearchRequest(message) ? AgentTool.WEB_SEARCH : AgentTool.CHAT, message);
    }
}
