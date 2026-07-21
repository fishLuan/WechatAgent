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
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ImageGenerator {

    private final String baseUrl;
    private final String apiKey;
    private final String model;
    private final String size;
    private final int n;
    private static final ObjectMapper mapper = new ObjectMapper();

    private static final String[] FALLBACK_URLS = new String[]{
        "https://dashscope.aliyuncs.com/api/v1/services/aigc/text2image/image-synthesis",
        "https://dashscope.aliyuncs.com/compatible-mode/v1/images/generations",
        "https://dashscope.aliyuncs.com/api/v1/services/aigc/multimodal-generation/generation",
    };

    public ImageGenerator(String baseUrl, String apiKey, String model, String size, int n) {
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        this.model = model;
        this.size = size;
        this.n = n;
    }

    public byte[] generate(String prompt) throws Exception {
        // 5 种请求体，覆盖 DashScope 和 OpenAI 兼容格式
        String jsonA = buildBodyNested(prompt, size, false);
        String jsonB = buildBodyFlat(prompt, size.replace('*', 'x'));
        String jsonC = buildBodyMessages(prompt, size.replace('*', 'x'), false);
        String jsonD = buildBodyMessages(prompt, size, false);
        String jsonE = buildBodyMessages(prompt, size, true);

        String[][] bodies = new String[][]{
            {jsonA, "DashScope嵌套prompt"},
            {jsonB, "OpenAI扁平"},
            {jsonC, "消息格式size=x"},
            {jsonD, "消息格式size=*"},
            {jsonE, "消息格式content数组"},
        };

        // 候选 URL：配置中的 baseUrl + 3 个备用 URL
        List<String> urls = new ArrayList<>();
        if (baseUrl != null && !baseUrl.isEmpty()) urls.add(baseUrl);
        for (String u : FALLBACK_URLS) if (!urls.contains(u)) urls.add(u);

        // 遍历尝试：每个 URL × 每个请求体
        Exception lastError = null;
        for (String url : urls) {
            for (String[] bd : bodies) {
                String body = bd[0];
                try {
                    String respRaw = postRaw(url, body, apiKey);
                    JsonNode root = mapper.readTree(respRaw);

                    // 解析 1: data[0].url / b64_json
                    JsonNode data = root.path("data");
                    if (data.isArray() && data.size() > 0) {
                        JsonNode first = data.get(0);
                        if (first.has("url")) return TextLlmService.downloadImage(cleanUrl(first.get("url").asText()));
                        if (first.has("b64_json")) return Base64.getDecoder().decode(first.get("b64_json").asText(""));
                    }

                    // 解析 2: output.results[0].url / b64_image
                    JsonNode output = root.path("output");
                    if (output.has("results") && output.get("results").isArray()) {
                        JsonNode first = output.get("results").get(0);
                        if (first.has("url")) return TextLlmService.downloadImage(cleanUrl(first.get("url").asText()));
                        if (first.has("b64_image")) return Base64.getDecoder().decode(first.get("b64_image").asText(""));
                    }

                    // 解析 3: output.url
                    if (output.has("url")) return TextLlmService.downloadImage(cleanUrl(output.get("url").asText()));

                    // 解析 4: 顶层 url
                    if (root.has("url")) return TextLlmService.downloadImage(cleanUrl(root.get("url").asText()));

                    // 解析 5: output.choices[0].message.content[0].image (新版 multimodal)
                    JsonNode choices = output.path("choices");
                    if (choices.isArray() && choices.size() > 0) {
                        JsonNode contentArr = choices.get(0).path("message").path("content");
                        if (contentArr.isArray() && contentArr.size() > 0 && contentArr.get(0).has("image")) {
                            return TextLlmService.downloadImage(cleanUrl(contentArr.get(0).get("image").asText()));
                        }
                    }

                    lastError = new Exception("HTTP 200 但解析失败: " + respRaw.substring(0, Math.min(300, respRaw.length())));
                } catch (Exception e) {
                    lastError = e;
                }
            }
        }

        if (lastError != null) throw lastError;
        throw new Exception("图片生成失败，请检查配置");
    }

    // ============ 请求体构造 ============

    private String buildBodyNested(String prompt, String sz, boolean unused) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("prompt", prompt);
        body.put("input", input);
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("size", sz);
        params.put("n", n);
        body.put("parameters", params);
        try { return mapper.writeValueAsString(body); } catch (Exception e) { return ""; }
    }

    private String buildBodyFlat(String prompt, String sz) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("prompt", prompt);
        body.put("size", sz);
        body.put("n", n);
        try { return mapper.writeValueAsString(body); } catch (Exception e) { return ""; }
    }

    private String buildBodyMessages(String prompt, String sz, boolean contentAsArray) {
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("role", "user");
        if (contentAsArray) {
            Map<String, Object> textItem = new LinkedHashMap<>();
            textItem.put("type", "text");
            textItem.put("text", prompt);
            msg.put("content", new Object[]{textItem});
        } else {
            msg.put("content", prompt);
        }
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("messages", new Object[]{msg});

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("input", input);
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("size", sz);
        params.put("n", n);
        body.put("parameters", params);
        try { return mapper.writeValueAsString(body); } catch (Exception e) { return ""; }
    }

    // ============ 工具方法 ============

    private static String cleanUrl(String url) {
        if (url == null) return "";
        return url.replaceAll("^`|`$", "").trim();
    }

    private static String postRaw(String urlStr, String jsonBody, String key) throws Exception {
        URL url = URI.create(urlStr).toURL();
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(30000);
        conn.setReadTimeout(120000);
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        conn.setRequestProperty("Authorization", "Bearer " + key);
        conn.setDoOutput(true);
        try (OutputStream os = conn.getOutputStream()) { os.write(jsonBody.getBytes(StandardCharsets.UTF_8)); }
        int code = conn.getResponseCode();
        StringBuilder sb = new StringBuilder();
        java.io.InputStream is = (code >= 400) ? conn.getErrorStream() : conn.getInputStream();
        if (is != null) {
            try (BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
            }
        }
        if (code < 200 || code >= 300) throw new Exception("HTTP " + code + ": " + sb);
        return sb.toString();
    }
}