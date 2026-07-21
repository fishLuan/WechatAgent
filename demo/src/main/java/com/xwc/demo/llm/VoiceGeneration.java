package com.xwc.demo.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * TTS 语音生成 — 文字转 MP3 音频字节。
 * 通过阿里云百炼 / DashScope 多模态端点调用 qwen3-tts-flash。
 */
public class VoiceGeneration {

    private static final ObjectMapper mapper = new ObjectMapper();
    private static final String DEFAULT_URL =
            "https://dashscope.aliyuncs.com/api/v1/services/aigc/multimodal-generation/generation";

    public static VoiceResult generate(String text, String voice, String apiKey,
                                       String baseUrl, String model, String format) throws Exception {
        if (text == null || text.trim().isEmpty())
            throw new IllegalArgumentException("文字不能为空");
        if (apiKey == null || apiKey.isEmpty())
            throw new IllegalArgumentException("TTS API Key 不能为空");

        String modelName = notEmpty(model) ? model : "qwen3-tts-flash";
        String voiceName = notEmpty(voice) ? voice : "Cherry";
        String cleanText = cleanText(text.trim());
        String url = notEmpty(baseUrl) ? baseUrl : DEFAULT_URL;

        System.out.println("  [TTS] → " + modelName + " | voice=" + voiceName);
        byte[] audio = callTts(url, modelName, cleanText, voiceName, apiKey);

        VoiceResult r = new VoiceResult();
        r.audioBytes = audio;
        r.fileName = "tts_" + System.currentTimeMillis() + ".mp3";
        r.playTimeMs = Math.max(1000, (audio.length / 16000) * 1000);
        System.out.println("  [TTS] ✅ " + audio.length + " 字节");
        return r;
    }

    private static byte[] callTts(String url, String model, String text,
                                  String voice, String apiKey) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("text", text);
        input.put("voice", voice);
        input.put("language_type", "Chinese");
        body.put("input", input);

        String jsonBody = mapper.writeValueAsString(body);
        HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(90000);
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        conn.setRequestProperty("Authorization", "Bearer " + apiKey);
        conn.setDoOutput(true);

        try (java.io.OutputStream os = conn.getOutputStream()) {
            os.write(jsonBody.getBytes(StandardCharsets.UTF_8));
        }

        int code = conn.getResponseCode();
        InputStream is = (code >= 400) ? conn.getErrorStream() : conn.getInputStream();
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        if (is != null) {
            byte[] buf = new byte[4096];
            int n;
            while ((n = is.read(buf)) != -1) baos.write(buf, 0, n);
            is.close();
        }
        byte[] raw = baos.toByteArray();

        if (code < 200 || code >= 300) {
            String err = new String(raw, StandardCharsets.UTF_8);
            if (err.length() > 300) err = err.substring(0, 300) + "...";
            throw new Exception("HTTP " + code + " | " + err);
        }

        // 响应解析：output.audio.url
        String resp = new String(raw, StandardCharsets.UTF_8).trim();
        if (resp.startsWith("{")) {
            JsonNode root = mapper.readTree(resp);
            String audioUrl = root.path("output").path("audio").path("url").asText(null);
            if (audioUrl != null && !audioUrl.isEmpty()) {
                return downloadAudio(audioUrl);
            }
            throw new Exception("未找到音频 URL: " + resp.substring(0, 200));
        }
        throw new Exception("非 JSON 响应: " + resp.substring(0, 200));
    }

    // ============ 工具方法 ============

    public static String deriveTtsUrl(String llmBaseUrl) {
        if (!notEmpty(llmBaseUrl)) return null;
        try {
            URL url = URI.create(llmBaseUrl).toURL();
            String host = url.getHost();
            if (host.contains("maas") || host.contains("dashscope") || host.contains("aliyun")) {
                return url.getProtocol() + "://" + host + "/api/v1/services/aigc/multimodal-generation/generation";
            }
        } catch (Exception ignored) {}
        return null;
    }

    private static String cleanText(String text) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            int charCount = Character.charCount(cp);
            boolean keep = (cp >= 0x4E00 && cp <= 0x9FFF)
                    || (cp >= 'a' && cp <= 'z') || (cp >= 'A' && cp <= 'Z')
                    || (cp >= '0' && cp <= '9')
                    || cp == ' ' || cp == ',' || cp == '.' || cp == '!' || cp == '?'
                    || cp == ';' || cp == ':'
                    || cp == 0x3001 || cp == 0x3002 || cp == 0xFF0C
                    || cp == 0xFF01 || cp == 0xFF1F || cp == 0xFF1A;
            if (keep) sb.appendCodePoint(cp);
            i += charCount;
        }
        String result = sb.toString().trim();
        return result.isEmpty() ? "你好" : result;
    }

    private static byte[] downloadAudio(String urlStr) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(60000);
        if (conn.getResponseCode() >= 300)
            throw new Exception("下载音频失败 HTTP " + conn.getResponseCode());
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (InputStream is = conn.getInputStream()) {
            byte[] buf = new byte[4096];
            int n;
            while ((n = is.read(buf)) != -1) baos.write(buf, 0, n);
        }
        return baos.toByteArray();
    }

    private static boolean notEmpty(String s) {
        return s != null && !s.isEmpty();
    }

    // ============ 结果类 ============

    public static class VoiceResult {
        public byte[] audioBytes;
        public String fileName;
        public int playTimeMs;
    }
}
