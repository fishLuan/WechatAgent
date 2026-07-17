package com.xwc.demo.llm.vision;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ImageReader {

    private final String baseUrl;
    private final String apiKey;
    private final String model;
    private static final ObjectMapper mapper = new ObjectMapper();

    public ImageReader(String baseUrl, String apiKey, String model) {
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        this.model = model;
    }

    /**
     * 理解图片内容
     * @param imageBytes 图片字节
     * @param question 用户问题（可为空）
     * @return 对图片的描述文字
     */
    public String understand(byte[] imageBytes, String question) throws Exception {
        String base64 = Base64.getEncoder().encodeToString(imageBytes);
        String userText = (question == null || question.isEmpty())
                ? "请描述这张图片" : question;

        // 构造带图片的消息格式
        List<Object> contentList = new java.util.ArrayList<>();

        // 图片部分
        Map<String, Object> imgPart = new LinkedHashMap<>();
        imgPart.put("type", "image_url");
        imgPart.put("image_url", Map.of("url", "data:image/jpeg;base64," + base64));
        contentList.add(imgPart);

        // 文字部分
        Map<String, Object> textPart = new LinkedHashMap<>();
        textPart.put("type", "text");
        textPart.put("text", userText);
        contentList.add(textPart);

        // 组装请求体
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("messages", List.of(Map.of("role", "user", "content", contentList)));

        String json = mapper.writeValueAsString(body);
        String resp = httpPost(json);

        JsonNode root = mapper.readTree(resp);
        return root.path("choices").get(0).path("message").path("content").asText("");
    }

    private String httpPost(String jsonBody) throws Exception {
        String url = baseUrl.endsWith("/") ? baseUrl + "chat/completions" : baseUrl + "/chat/completions";
        HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
        conn.setConnectTimeout(30000);
        conn.setReadTimeout(60000);
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        conn.setRequestProperty("Authorization", "Bearer " + apiKey);
        conn.setDoOutput(true);
        try (OutputStream os = conn.getOutputStream()) {
            os.write(jsonBody.getBytes(StandardCharsets.UTF_8));
        }
        int code = conn.getResponseCode();
        BufferedReader br = new BufferedReader(new InputStreamReader(
                code >= 400 ? conn.getErrorStream() : conn.getInputStream(), StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = br.readLine()) != null) sb.append(line);
        if (code < 200 || code >= 300) throw new RuntimeException("HTTP " + code + ": " + sb);
        return sb.toString();
    }
}
