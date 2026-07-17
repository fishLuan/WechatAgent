package com.xwc.demo.llm.vision;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

public class ImageGenerator {
    private final String apiUrl;
    private final String apiKey;
    private final String model;
    private static final ObjectMapper mapper = new ObjectMapper();

    public ImageGenerator(String apiUrl, String apiKey, String model) {
        this.apiUrl = apiUrl;
        this.apiKey = apiKey;
        this.model = model;
    }

    /**
     * 根据文字描述生成图片
     * @param prompt 绘画提示词
     * @return 图片的字节数组
     */
    public byte[] generate(String prompt) throws Exception {
        // 1. 调用文生图 API
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("prompt", prompt);

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("size", "1024*1024");
        params.put("n", 1);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("input", input);
        body.put("parameters", params);

        String json = mapper.writeValueAsString(body);

        // 发送请求
        HttpURLConnection conn = (HttpURLConnection) URI.create(apiUrl).toURL().openConnection();
        conn.setConnectTimeout(30000);
        conn.setReadTimeout(120000);  // 生图可能比较慢
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        conn.setRequestProperty("Authorization", "Bearer " + apiKey);
        conn.setDoOutput(true);
        try (OutputStream os = conn.getOutputStream()) {
            os.write(json.getBytes(StandardCharsets.UTF_8));
        }

        int code = conn.getResponseCode();
        BufferedReader br = new BufferedReader(new InputStreamReader(
                code >= 400 ? conn.getErrorStream() : conn.getInputStream(), StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = br.readLine()) != null) sb.append(line);

        if (code < 200 || code >= 300) throw new RuntimeException("HTTP " + code + ": " + sb);

        // 2. 解析响应，获取图片 URL
        JsonNode root = mapper.readTree(sb.toString());
        String imageUrl = root.path("output")
                .path("results")
                .get(0)
                .path("url")
                .asText("");

        if (imageUrl.isEmpty()) throw new RuntimeException("没有返回图片 URL: " + sb);

        System.out.println("[ImageGenerator] 图片URL: " + imageUrl);

        // 3. 下载图片
        return downloadImage(imageUrl);
    }

    private byte[] downloadImage(String imageUrl) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) URI.create(imageUrl).toURL().openConnection();
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(60000);
        conn.setRequestMethod("GET");

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (InputStream is = conn.getInputStream()) {
            byte[] buf = new byte[4096];
            int n;
            while ((n = is.read(buf)) != -1) baos.write(buf, 0, n);
        }
        return baos.toByteArray();
    }
}
