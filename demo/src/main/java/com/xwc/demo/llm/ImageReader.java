package com.xwc.demo.llm;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
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

    public String understand(byte[] imageBytes, String question) throws Exception {
        String base64 = java.util.Base64.getEncoder().encodeToString(imageBytes);
        String mimeType = detectMimeType(imageBytes);
        String userText = (question == null || question.isEmpty()) ? "请描述这张图片" : question;

        Map<String, Object> textPart = new LinkedHashMap<>();
        textPart.put("type", "text");
        textPart.put("text", userText);

        Map<String, Object> imagePart = new LinkedHashMap<>();
        imagePart.put("type", "image_url");
        imagePart.put("image_url", Map.of("url", "data:" + mimeType + ";base64," + base64));

        Map<String, Object> userMsg = new LinkedHashMap<>();
        userMsg.put("role", "user");
        userMsg.put("content", new Object[]{textPart, imagePart});

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("messages", new Object[]{userMsg});

        String json = mapper.writeValueAsString(body);
        String resp = TextLlmService.httpPost(baseUrl + "/chat/completions", json, apiKey);
        return TextLlmService.extractContent(resp);
    }

    private String detectMimeType(byte[] data) {
        if (data == null || data.length < 8) return "image/jpeg";
        if (data[0] == (byte)0x89 && data[1] == 0x50 && data[2] == 0x47 && data[3] == 0x4E) return "image/png";
        if (data[0] == (byte)0xFF && data[1] == (byte)0xD8) return "image/jpeg";
        if (data[0] == 0x47 && data[1] == 0x49 && data[2] == 0x46) return "image/gif";
        return "image/jpeg";
    }
}