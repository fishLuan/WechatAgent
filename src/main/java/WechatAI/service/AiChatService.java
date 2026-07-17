package WechatAI.service;

import WechatAI.config.AiProperties;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import java.util.concurrent.TimeUnit;

public class AiChatService {

    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    private final AiProperties properties;
    private final OkHttpClient httpClient;
    private final Gson gson;

    public AiChatService(AiProperties properties) {
        this(properties, defaultHttpClient(), new Gson());
    }

    public AiChatService(AiProperties properties, OkHttpClient httpClient, Gson gson) {
        this.properties = properties;
        this.httpClient = httpClient;
        this.gson = gson;
    }

    public String chat(String userMessage) {
        try {
            Request request = new Request.Builder()
                    .url(properties.getTextApiUrl())
                    .addHeader("Authorization", "Bearer " + properties.getTextApiKey())
                    .addHeader("Content-Type", "application/json")
                    .post(RequestBody.create(JSON, gson.toJson(buildRequestBody(userMessage))))
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    System.err.println("❌ API请求失败: " + response.code() + " - " + response.message());
                    return null;
                }

                if (response.body() == null) {
                    System.err.println("❌ API响应为空");
                    return null;
                }

                return parseReply(response.body().string());
            }
        } catch (Exception e) {
            System.err.println("❌ 调用大模型失败: " + e.getMessage());
            e.printStackTrace();
            return null;
        }
    }

    private JsonObject buildRequestBody(String userMessage) {
        JsonObject requestBody = new JsonObject();
        requestBody.addProperty("model", properties.getTextModel());
        requestBody.add("messages", buildMessages(userMessage));
        requestBody.addProperty("max_tokens", 500);
        requestBody.addProperty("temperature", 0.7);
        return requestBody;
    }

    private JsonArray buildMessages(String userMessage) {
        JsonArray messages = new JsonArray();

        JsonObject systemMessage = new JsonObject();
        systemMessage.addProperty("role", "system");
        systemMessage.addProperty("content", properties.getSystemPrompt());
        messages.add(systemMessage);

        JsonObject userMsg = new JsonObject();
        userMsg.addProperty("role", "user");
        userMsg.addProperty("content", userMessage);
        messages.add(userMsg);

        return messages;
    }

    private String parseReply(String responseBody) {
        JsonObject jsonResponse = gson.fromJson(responseBody, JsonObject.class);
        JsonArray choices = jsonResponse.getAsJsonArray("choices");
        if (choices == null || choices.size() == 0) {
            System.err.println("❌ 解析API响应失败");
            return null;
        }

        JsonObject firstChoice = choices.get(0).getAsJsonObject();
        JsonObject message = firstChoice.getAsJsonObject("message");
        if (message == null || !message.has("content")) {
            System.err.println("❌ 解析API响应失败");
            return null;
        }

        return message.get("content").getAsString();
    }

    private static OkHttpClient defaultHttpClient() {
        return new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .build();
    }
}
