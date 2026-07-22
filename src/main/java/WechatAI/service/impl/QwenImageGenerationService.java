package WechatAI.service.impl;

import WechatAI.config.AiProperties;
import WechatAI.service.ImageGenerationService;
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
 * 千问图片生成实现，封装 DashScope 生图接口调用和结果解析。
 */
public class QwenImageGenerationService implements ImageGenerationService {

    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
    private static final String QUOTA_HINT = "当前图片生成模型可能没有开通免费额度或付费额度，请在阿里云百炼开通 qwen-image 后再使用。";

    private final AiProperties properties;
    private final OkHttpClient httpClient;
    private final Gson gson;
    private String lastErrorMessage;

    public QwenImageGenerationService(AiProperties properties) {
        this(properties, defaultHttpClient(), new Gson());
    }

    public QwenImageGenerationService(AiProperties properties, OkHttpClient httpClient, Gson gson) {
        this.properties = properties;
        this.httpClient = httpClient;
        this.gson = gson;
    }

    @Override
    public byte[] generate(String prompt) {
        lastErrorMessage = null;
        if (!hasApiKey(properties.getImageGenerationApiKey())) {
            setLastErrorMessage("图片生成模型还没有配置 API Key，请填写 qwen.api.key 或 qwen.image.generation.api.key。");
            return null;
        }

        try {
            Request request = new Request.Builder()
                    .url(properties.getImageGenerationApiUrl())
                    .addHeader("Authorization", "Bearer " + properties.getImageGenerationApiKey())
                    .addHeader("Content-Type", "application/json")
                    .post(RequestBody.create(JSON, gson.toJson(buildRequestBody(prompt))))
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    String errorBody = response.body() == null ? "" : response.body().string();
                    System.err.println("❌ 图片生成API请求失败: " + response.code() + " - " + response.message());
                    if (!errorBody.isEmpty()) {
                        System.err.println("❌ 图片生成API错误详情: " + errorBody);
                    }
                    if (response.code() == 403) {
                        setLastErrorMessage(QUOTA_HINT);
                    } else {
                        setLastErrorMessage("图片生成API请求失败：" + response.code() + " - " + response.message() + formatErrorBody(errorBody));
                    }
                    return null;
                }
                if (response.body() == null) {
                    setLastErrorMessage("图片生成API响应为空。");
                    return null;
                }
                return parseImageBytes(response.body().string());
            }
        } catch (Exception e) {
            setLastErrorMessage("调用图片生成模型失败：" + e.getMessage());
            e.printStackTrace();
            return null;
        }
    }

    @Override
    public String getLastErrorMessage() {
        return lastErrorMessage;
    }

    private JsonObject buildRequestBody(String prompt) {
        JsonObject requestBody = new JsonObject();
        requestBody.addProperty("model", properties.getImageGenerationModel());

        JsonObject input = new JsonObject();
        JsonArray messages = new JsonArray();
        JsonObject message = new JsonObject();
        message.addProperty("role", "user");

        JsonArray content = new JsonArray();
        JsonObject textPart = new JsonObject();
        textPart.addProperty("text", prompt);
        content.add(textPart);

        message.add("content", content);
        messages.add(message);
        input.add("messages", messages);
        requestBody.add("input", input);

        JsonObject parameters = new JsonObject();
        parameters.addProperty("size", "1328*1328");
        parameters.addProperty("n", 1);
        parameters.addProperty("watermark", false);
        requestBody.add("parameters", parameters);
        return requestBody;
    }

    private String formatErrorBody(String errorBody) {
        if (errorBody == null || errorBody.isEmpty()) {
            return "";
        }
        return "，错误详情：" + errorBody;
    }

    private byte[] parseImageBytes(String responseBody) {
        JsonObject jsonResponse = gson.fromJson(responseBody, JsonObject.class);
        if (jsonResponse.has("output")) {
            byte[] dashScopeImage = parseDashScopeImageBytes(jsonResponse.getAsJsonObject("output"));
            if (dashScopeImage != null) {
                return dashScopeImage;
            }
        }

        JsonArray data = jsonResponse.getAsJsonArray("data");
        if (data == null || data.size() == 0) {
            setLastErrorMessage("解析图片生成响应失败，接口没有返回图片数据。");
            return null;
        }

        JsonObject firstImage = data.get(0).getAsJsonObject();
        if (firstImage.has("b64_json")) {
            return Base64.getDecoder().decode(firstImage.get("b64_json").getAsString());
        }
        if (firstImage.has("url")) {
            return download(firstImage.get("url").getAsString());
        }

        setLastErrorMessage("图片生成响应中没有 b64_json 或 url。");
        return null;
    }

    private byte[] parseDashScopeImageBytes(JsonObject output) {
        JsonArray choices = output.getAsJsonArray("choices");
        if (choices != null && choices.size() > 0) {
            JsonObject firstChoice = choices.get(0).getAsJsonObject();
            JsonObject message = firstChoice.getAsJsonObject("message");
            if (message != null && message.has("content")) {
                JsonArray content = message.getAsJsonArray("content");
                for (int i = 0; i < content.size(); i++) {
                    JsonObject item = content.get(i).getAsJsonObject();
                    if (item.has("image")) {
                        return download(item.get("image").getAsString());
                    }
                    if (item.has("url")) {
                        return download(item.get("url").getAsString());
                    }
                }
            }
        }

        JsonArray results = output.getAsJsonArray("results");
        if (results != null && results.size() > 0) {
            JsonObject firstResult = results.get(0).getAsJsonObject();
            if (firstResult.has("url")) {
                return download(firstResult.get("url").getAsString());
            }
        }
        return null;
    }

    private byte[] download(String imageUrl) {
        Request request = new Request.Builder().url(imageUrl).build();
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                setLastErrorMessage("生成图片下载失败：" + response.code() + " - " + response.message());
                return null;
            }
            return response.body().bytes();
        } catch (Exception e) {
            setLastErrorMessage("下载生成图片失败：" + e.getMessage());
            return null;
        }
    }

    private void setLastErrorMessage(String message) {
        lastErrorMessage = message;
        System.err.println("❌ " + message);
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
                .readTimeout(180, TimeUnit.SECONDS)
                .writeTimeout(180, TimeUnit.SECONDS)
                .build();
    }
}
