package com.xwc.demo.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class TextLlmService {

    private final String baseUrl;
    private final String apiKey;
    private final String model;
    private final Map<String, List<LlmMsg>> memory = new ConcurrentHashMap<>();
    private static final ObjectMapper mapper = new ObjectMapper();

    public TextLlmService(String baseUrl, String apiKey, String model) {
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        this.model = model;
    }

    public String chat(String userId, String text) throws Exception {
        List<LlmMsg> history = memory.computeIfAbsent(userId, k -> new ArrayList<>());
        history.add(new LlmMsg("user", text));
        while (history.size() > 20) history.remove(0);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("messages", buildMessages(history));

        String json = mapper.writeValueAsString(body);
        String resp = httpPost(baseUrl + "/chat/completions", json, apiKey);
        String reply = extractContent(resp);

        history.add(new LlmMsg("assistant", reply));
        while (history.size() > 20) history.remove(0);
        return reply;
    }

    private List<Map<String, Object>> buildMessages(List<LlmMsg> history) {
        List<Map<String, Object>> msgs = new ArrayList<>();
        Map<String, Object> sys = new LinkedHashMap<>();
        sys.put("role", "system");
        sys.put("content", "你是微信助手，用中文简短回复");
        msgs.add(sys);
        for (LlmMsg m : history) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("role", m.role);
            item.put("content", m.content);
            msgs.add(item);
        }
        return msgs;
    }

    public void clearMemory(String userId) { memory.remove(userId); }

    // ---------- 通用工具方法 ----------

    static String httpPost(String endpoint, String jsonBody, String apiKey) throws Exception {
        URL url = URI.create(endpoint).toURL();
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(30000);
        conn.setReadTimeout(120000);
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        conn.setRequestProperty("Authorization", "Bearer " + apiKey);
        conn.setDoOutput(true);
        try (OutputStream os = conn.getOutputStream()) {
            os.write(jsonBody.getBytes(StandardCharsets.UTF_8));
        }
        int code = conn.getResponseCode();
        InputStreamReader isr = new InputStreamReader(
                code >= 400 ? conn.getErrorStream() : conn.getInputStream(), StandardCharsets.UTF_8);
        BufferedReader br = new BufferedReader(isr);
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = br.readLine()) != null) sb.append(line);
        if (code < 200 || code >= 300) throw new Exception("HTTP " + code + ": " + sb);
        return sb.toString();
    }

    static String extractContent(String json) throws Exception {
        JsonNode root = mapper.readTree(json);
        return root.path("choices").get(0).path("message").path("content").asText("");
    }

    static byte[] downloadImage(String imageUrl) throws Exception {
        URL url = new URL(imageUrl);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(60000);
        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        try (java.io.InputStream is = conn.getInputStream()) {
            byte[] buf = new byte[4096];
            int n;
            while ((n = is.read(buf)) != -1) baos.write(buf, 0, n);
        }
        return baos.toByteArray();
    }

    static class LlmMsg {
        final String role, content;
        LlmMsg(String role, String content) { this.role = role; this.content = content; }
    }
}