package com.luanxv.pre.wechatAI.service.impl;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
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
public class QwenImageGenService {
    private final BotConfig config;
    private final OkHttpClient httpClient;
    private final Gson gson;

    public QwenImageGenService(BotConfig config) {
        this.config = config;
        this.gson = new Gson();
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(60, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .writeTimeout(120, TimeUnit.SECONDS)
                .build();
    }

    public byte[] generateImage(String prompt) {
        return requestImage(prompt, null);
    }

    /**
     * The image is supplied as a data URL. This keeps the cached source image
     * private and avoids needing any public image hosting service.
     */
    public byte[] editImage(byte[] sourceImage, String instruction) {
        if (sourceImage == null || sourceImage.length == 0) {
            return null;
        }
        String prompt = "请基于输入图片进行编辑，保留未被明确要求修改的内容。用户的修改要求：" + instruction;
        return requestImage(prompt, sourceImage);
    }

    private byte[] requestImage(String prompt, byte[] sourceImage) {
        try {
            Request request = buildHttpRequest(buildPayload(prompt, sourceImage));
            try (Response response = httpClient.newCall(request).execute()) {
                return parseResponse(response);
            }
        } catch (Exception exception) {
            System.err.println("图片生成或编辑失败: " + exception.getMessage());
            return null;
        }
    }

    private JsonObject buildPayload(String prompt, byte[] sourceImage) {
        JsonObject body = new JsonObject();
        body.addProperty("model", config.getQwenImageModel());

        JsonObject input = new JsonObject();
        JsonArray messages = new JsonArray();
        JsonObject userMessage = new JsonObject();
        userMessage.addProperty("role", "user");
        JsonArray content = new JsonArray();
        JsonObject text = new JsonObject();
        text.addProperty("text", prompt);
        content.add(text);
        if (sourceImage != null) {
            JsonObject image = new JsonObject();
            image.addProperty("image", "data:" + detectImageMimeType(sourceImage) + ";base64,"
                    + Base64.getEncoder().encodeToString(sourceImage));
            content.add(image);
        }
        userMessage.add("content", content);
        messages.add(userMessage);
        input.add("messages", messages);
        body.add("input", input);

        JsonObject parameters = new JsonObject();
        parameters.addProperty("size", "2K");
        parameters.addProperty("n", 1);
        parameters.addProperty("watermark", false);
        body.add("parameters", parameters);
        return body;
    }

    private Request buildHttpRequest(JsonObject body) {
        return new Request.Builder()
                .url(config.getQwenImageUrl())
                .addHeader("Authorization", "Bearer " + config.getQwenApiKey())
                .addHeader("Content-Type", "application/json")
                .post(RequestBody.create(MediaType.parse("application/json; charset=utf-8"), gson.toJson(body)))
                .build();
    }

    private byte[] parseResponse(Response response) throws IOException {
        String responseBody = response.body() == null ? "" : response.body().string();
        if (!response.isSuccessful()) {
            System.err.println("图片生成或编辑请求失败: " + response.code() + ", " + responseBody);
            return null;
        }
        JsonObject root = gson.fromJson(responseBody, JsonObject.class);
        JsonObject output = root == null ? null : root.getAsJsonObject("output");
        JsonArray choices = output == null ? null : output.getAsJsonArray("choices");
        if (choices == null || choices.isEmpty()) {
            System.err.println("图片生成或编辑响应中没有图片: " + responseBody);
            return null;
        }
        JsonObject message = choices.get(0).getAsJsonObject().getAsJsonObject("message");
        JsonArray content = message == null ? null : message.getAsJsonArray("content");
        if (content != null) {
            for (int index = 0; index < content.size(); index++) {
                JsonObject item = content.get(index).getAsJsonObject();
                if (item.has("image")) {
                    return downloadImage(item.get("image").getAsString());
                }
            }
        }
        System.err.println("图片生成或编辑响应中没有 image 字段: " + responseBody);
        return null;
    }

    private String detectImageMimeType(byte[] bytes) {
        if (bytes.length >= 3 && bytes[0] == (byte) 0xFF && bytes[1] == (byte) 0xD8 && bytes[2] == (byte) 0xFF) {
            return "image/jpeg";
        }
        if (bytes.length >= 12 && bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == 'F'
                && bytes[8] == 'W' && bytes[9] == 'E' && bytes[10] == 'B' && bytes[11] == 'P') {
            return "image/webp";
        }
        if (bytes.length >= 2 && bytes[0] == 'B' && bytes[1] == 'M') {
            return "image/bmp";
        }
        return "image/png";
    }

    private byte[] downloadImage(String imageUrl) throws IOException {
        if (imageUrl.startsWith("data:")) {
            int comma = imageUrl.indexOf(',');
            return comma < 0 ? null : Base64.getDecoder().decode(imageUrl.substring(comma + 1));
        }
        Request request = new Request.Builder().url(imageUrl).get().build();
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                throw new IOException("下载图片失败: " + response.code());
            }
            return response.body().bytes();
        }
    }
}
