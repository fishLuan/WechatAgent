package WechatAI.service.impl;

import WechatAI.config.AiProperties;
import WechatAI.service.ImageUnderstandingService;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import java.util.Base64;
import java.util.concurrent.TimeUnit;

/**
 * 千问视觉理解实现，负责把图片编码为模型可识别的多模态输入。
 */
public class QwenImageUnderstandingService implements ImageUnderstandingService {

    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
    private static final String DEFAULT_PROMPT = "请用中文简洁描述这张图片，并指出图片中值得注意的细节。";

    private final AiProperties properties;
    private final OkHttpClient httpClient;
    private final Gson gson;

    public QwenImageUnderstandingService(AiProperties properties) {
        this(properties, defaultHttpClient(), new Gson());
    }

    public QwenImageUnderstandingService(AiProperties properties, OkHttpClient httpClient, Gson gson) {
        this.properties = properties;
        this.httpClient = httpClient;
        this.gson = gson;
    }

    @Override
    public String understand(byte[] imageBytes, String prompt) {
        if (!hasApiKey(properties.getVisionApiKey())) {
            return "图片理解模型还没有配置 API Key，请先在 config.properties 中填写 qwen.api.key 或 qwen.vision.api.key。";
        }

        try {
            Request request = new Request.Builder()
                    .url(properties.getVisionApiUrl())
                    .addHeader("Authorization", "Bearer " + properties.getVisionApiKey())
                    .addHeader("Content-Type", "application/json")
                    .post(RequestBody.create(JSON, gson.toJson(buildRequestBody(imageBytes, prompt))))
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    System.err.println("❌ 图片理解API请求失败: " + response.code() + " - " + response.message());
                    return null;
                }
                if (response.body() == null) {
                    System.err.println("❌ 图片理解API响应为空");
                    return null;
                }
                return parseReply(response.body().string());
            }
        } catch (Exception e) {
            System.err.println("❌ 调用图片理解模型失败: " + e.getMessage());
            e.printStackTrace();
            return null;
        }
    }

    private JsonObject buildRequestBody(byte[] imageBytes, String prompt) {
        JsonObject requestBody = new JsonObject();
        requestBody.addProperty("model", properties.getVisionModel());
        requestBody.add("messages", buildMessages(imageBytes, prompt));
        requestBody.addProperty("max_tokens", 800);
        requestBody.addProperty("temperature", 0.3);
        return requestBody;
    }

    private JsonArray buildMessages(byte[] imageBytes, String prompt) {
        JsonArray messages = new JsonArray();

        JsonObject userMessage = new JsonObject();
        userMessage.addProperty("role", "user");

        JsonArray content = new JsonArray();
        JsonObject textPart = new JsonObject();
        textPart.addProperty("type", "text");
        textPart.addProperty("text", isBlank(prompt) ? DEFAULT_PROMPT : prompt);
        content.add(textPart);

        JsonObject imagePart = new JsonObject();
        imagePart.addProperty("type", "image_url");
        JsonObject imageUrl = new JsonObject();
        imageUrl.addProperty("url", "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(imageBytes));
        imagePart.add("image_url", imageUrl);
        content.add(imagePart);

        userMessage.add("content", content);
        messages.add(userMessage);
        return messages;
    }

    private String parseReply(String responseBody) {
        JsonObject jsonResponse = gson.fromJson(responseBody, JsonObject.class);
        JsonArray choices = jsonResponse.getAsJsonArray("choices");
        if (choices == null || choices.size() == 0) {
            return null;
        }

        JsonObject message = choices.get(0).getAsJsonObject().getAsJsonObject("message");
        if (message == null || !message.has("content")) {
            return null;
        }
        return message.get("content").getAsString();
    }

    private static boolean hasApiKey(String apiKey) {
        return !isBlank(apiKey) && !"sk-your-actual-api-key-here".equals(apiKey);
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static OkHttpClient defaultHttpClient() {
        return new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(90, TimeUnit.SECONDS)
                .writeTimeout(90, TimeUnit.SECONDS)
                .build();
    }
}
