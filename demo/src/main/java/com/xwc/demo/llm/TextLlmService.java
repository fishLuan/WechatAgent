package com.xwc.demo.llm;


import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

// 职责：纯文本对话 + 多轮记忆管理
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
        // 存入历史
        List<LlmMsg> history = memory.computeIfAbsent(userId, k -> new ArrayList<>());
        history.add(new LlmMsg("user", text));
        trimHistory(history);

        // 构造请求
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("messages", buildMessages(history));

        String json = mapper.writeValueAsString(body);
        String resp = httpPost(json);
        String reply = extractContent(resp);

        // 存入回复
        history.add(new LlmMsg("assistant", reply));
        trimHistory(history);
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

    private String httpPost(String jsonBody) throws Exception {
        String url = baseUrl.endsWith("/") ? baseUrl + "chat/completions" : baseUrl + "/chat/completions";
        HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
        conn.setConnectTimeout(15000);
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

    private String extractContent(String json) throws Exception {
        JsonNode root = mapper.readTree(json);
        return root.path("choices").get(0).path("message").path("content").asText("");
    }

    private void trimHistory(List<LlmMsg> list) {
        while (list.size() > 20) list.remove(0);  // 最多10轮
    }

    public void clearMemory(String userId) { memory.remove(userId); }

    public static class LlmMsg {
        final String role, content;
        LlmMsg(String role, String content) { this.role = role; this.content = content; }
    }
}
