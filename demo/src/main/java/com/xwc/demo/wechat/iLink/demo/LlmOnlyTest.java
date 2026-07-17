package com.xwc.demo.wechat.iLink.demo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;

public class LlmOnlyTest {

    private static final ObjectMapper objectMapper = new ObjectMapper();

    public static void main(String[] args) {
        System.out.println("==== LLM 连通性测试 ====");

        // 1. 读取配置
        String baseUrl = "", apiKey = "", model = "";
        try {
            Properties props = new Properties();
            java.nio.file.Path p = Paths.get("config.properties");
            if (Files.exists(p)) {
                try (FileReader fr = new FileReader(p.toFile())) {
                    props.load(fr);
                }
                System.out.println("[配置] 从项目根目录 config.properties 加载");
            } else {
                try (InputStream is = LlmOnlyTest.class.getClassLoader().getResourceAsStream("config.properties")) {
                    if (is != null) {
                        props.load(is);
                        System.out.println("[配置] 从 classpath:config.properties 加载");
                    } else {
                        System.out.println("[配置] ❌ 找不到 config.properties！");
                    }
                }
            }
            baseUrl = props.getProperty("llm.base-url", "").trim();
            apiKey  = props.getProperty("llm.api-key", "").trim();
            model   = props.getProperty("llm.model", "").trim();
        } catch (Exception e) {
            System.out.println("[配置] 读取失败: " + e.getMessage());
            return;
        }

        System.out.println("[配置] base-url = " + baseUrl);
        System.out.println("[配置] api-key  = " + (apiKey.isEmpty() ? "空" : apiKey.substring(0, Math.min(8, apiKey.length())) + "****"));
        System.out.println("[配置] model    = " + model);

        if (baseUrl.isEmpty() || apiKey.isEmpty() || model.isEmpty()) {
            System.out.println("\n❌ 配置不完整，请先配置 config.properties");
            return;
        }

        // 2. 构造请求
        String userMsg = "你好，用3个字回复";
        System.out.println("\n[请求] 用户输入: \"" + userMsg + "\"");

        try {
            List<Map<String, Object>> messages = new ArrayList<>();
            Map<String, Object> sysMsg = new LinkedHashMap<>();
            sysMsg.put("role", "system");
            sysMsg.put("content", "你是一个简洁的助手，用中文回复。");
            messages.add(sysMsg);
            Map<String, Object> userMsgMap = new LinkedHashMap<>();
            userMsgMap.put("role", "user");
            userMsgMap.put("content", userMsg);
            messages.add(userMsgMap);

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", model);
            body.put("messages", messages);
            String jsonBody = objectMapper.writeValueAsString(body);

            System.out.println("[请求] 请求体: " + jsonBody.substring(0, Math.min(200, jsonBody.length())) + "...");

            // 3. 发送请求
            String endpoint = baseUrl.endsWith("/") ? baseUrl + "chat/completions" : baseUrl + "/chat/completions";
            System.out.println("[请求] POST " + endpoint);

            URL url = URI.create(endpoint).toURL();
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
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
            StringBuilder resp = new StringBuilder();
            try (InputStream is = (code >= 400) ? conn.getErrorStream() : conn.getInputStream();
                 BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) resp.append(line);
            }

            System.out.println("[响应] HTTP " + code);
            if (resp.length() > 500) {
                System.out.println("[响应] 内容: " + resp.substring(0, 500) + "...");
            } else {
                System.out.println("[响应] 内容: " + resp);
            }

            if (code >= 200 && code < 300) {
                JsonNode root = objectMapper.readTree(resp.toString());
                JsonNode choices = root.path("choices");
                if (choices.isArray() && choices.size() > 0) {
                    String reply = choices.get(0).path("message").path("content").asText("");
                    System.out.println("\n✅ 解析成功！模型回复: " + reply);
                } else {
                    System.out.println("\n⚠️  响应没有 choices[0].message.content，结构异常");
                }
            } else {
                System.out.println("\n❌ API 调用失败（HTTP " + code + "）");
                System.out.println("   常见原因:");
                System.out.println("   - 401: API Key 错误或过期");
                System.out.println("   - 404: base-url 路径不对（注意大多数 API 需要 /v1 结尾）");
                System.out.println("   - 429: 频率超限，或账户余额不足");
                System.out.println("   - 5xx: 服务商端问题，稍后重试");
            }

        } catch (Exception e) {
            System.out.println("\n❌ 异常: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            StringWriter sw = new StringWriter();
            e.printStackTrace(new PrintWriter(sw));
            String stack = sw.toString();
            System.out.println(stack.substring(0, Math.min(600, stack.length())));
        }
    }
}