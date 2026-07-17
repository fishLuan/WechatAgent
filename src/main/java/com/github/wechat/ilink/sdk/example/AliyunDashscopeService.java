package com.github.wechat.ilink.sdk.example;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;

/**
 * 阿里云百炼 (DashScope) API 服务封装
 *
 * 功能：
 *   1. 图片理解（看图 + 视觉问答） - 调用 qwen-vl-plus
 *   2. 文生图（根据文字描述生成图片） - 调用 wan2.6-t2i
 *
 * 使用方式：
 *   在环境变量中配置 DASHSCOPE_API_KEY = sk-xxxxxxxxxxxx
 *
 * API 文档参考：
 *   - 视觉理解: https://help.aliyun.com/zh/model-studio/vision
 *   - 文生图:    https://help.aliyun.com/zh/model-studio/text-to-image
 *
 * 代码风格：与 SimpleBot.java 保持一致
 *   - 使用 Java 11+ HttpClient
 *   - 手动构造 JSON 字符串
 *   - 手动解析 JSON 响应 (indexOf / substring)
 *   - 无第三方 JSON 库依赖
 */
public class AliyunDashscopeService {

    // ===== API 基础配置 =====
    private static final String API_BASE = "https://dashscope.aliyuncs.com/api/v1";
    // wan2.6-t2i 等新模型（文生图+看图）统一用 multimodal-generation 端点
    private static final String MULTIMODAL_ENDPOINT = API_BASE + "/services/aigc/multimodal-generation/generation";
    private static final String TASK_ENDPOINT = API_BASE + "/tasks/";

    // 模型名称
    private static final String VISION_MODEL = "qwen-vl-plus";
    private static final String IMAGE_MODEL = "wan2.6-t2i";

    // 文生图默认参数
    private static final String DEFAULT_IMAGE_SIZE = "1024*1024";
    private static final int DEFAULT_IMAGE_N = 1;

    // 异步轮询配置
    private static final int POLL_INTERVAL_MS = 5000;
    private static final int POLL_TIMEOUT_MS = 120000;

    // 成员变量
    private final String apiKey;
    private final HttpClient http;

    /**
     * 构造器
     * @param apiKey 阿里云百炼 API Key（sk- 开头），传 null 或空字符串表示未配置
     */
    public AliyunDashscopeService(String apiKey) {
        this.apiKey = (apiKey == null ? "" : apiKey.trim());
        this.http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();
    }

    /**
     * 是否已配置有效的 API Key
     */
    public boolean isConfigured() {
        return !apiKey.isEmpty();
    }

    /**
     * 图片理解：传入图片字节 + 问题，返回文字描述
     *
     * @param imageBytes 图片的二进制数据（JPEG/PNG 均可）
     * @param question   对图片的问题（如"请描述这张图片"、"这是什么"）
     * @return AI 返回的文字描述
     * @throws Exception 网络错误、API 返回非 200、响应解析失败等
     */
    public String understandImage(byte[] imageBytes, String question) throws Exception {
        if (imageBytes == null || imageBytes.length == 0) {
            throw new IllegalArgumentException("图片数据为空");
        }
        if (question == null || question.trim().isEmpty()) {
            question = "请描述这张图片的内容";
        }

        // 将图片转 Base64
        String base64 = Base64.getEncoder().encodeToString(imageBytes);
        String imageDataUrl = "data:image/jpeg;base64," + base64;

        // 构造请求体
        //
        // {
        //   "model": "qwen-vl-plus",
        //   "input": {
        //     "messages": [
        //       {
        //         "role": "user",
        //         "content": [
        //           {"image": "data:image/jpeg;base64,xxxx"},
        //           {"text": "请描述这张图片"}
        //         ]
        //       }
        //     ]
        //   }
        // }
        String body = "{"
            + "\"model\":\"" + VISION_MODEL + "\","
            + "\"input\":{"
            +     "\"messages\":["
            +         "{"
            +             "\"role\":\"user\","
            +             "\"content\":["
            +                 "{\"image\":" + jsonEscape(imageDataUrl) + "},"
            +                 "{\"text\":" + jsonEscape(question) + "}"
            +             "]"
            +         "}"
            +     "]"
            +   "}"
            + "}";

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(MULTIMODAL_ENDPOINT))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer " + apiKey)
            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
            .build();

        HttpResponse<String> response = http.send(request,
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

        if (response.statusCode() != 200) {
            String bodyPreview = response.body();
            if (bodyPreview.length() > 300) bodyPreview = bodyPreview.substring(0, 300) + "...";
            throw new Exception("视觉模型 API 返回 HTTP " + response.statusCode() + ": " + bodyPreview);
        }

        // 响应格式
        // {
        //   "output": {
        //     "choices": [
        //       { "message": { "content": "这里是图片描述..." } }
        //     ]
        //   },
        //   "usage": { ... },
        //   "request_id": "..."
        // }
        String content = extractContent(response.body());
        if (content == null || content.trim().isEmpty()) {
            // 尝试解析可能的 error 消息
            String errMsg = extractErrorMessage(response.body());
            if (errMsg != null && !errMsg.trim().isEmpty()) {
                throw new Exception("视觉模型 API 错误: " + errMsg);
            }
            throw new Exception("无法解析视觉模型 API 响应: " + response.body());
        }
        return content.trim();
    }

    /**
     * 文生图：根据文字描述生成图片（用默认参数：1024*1024, 1 张）
     *
     * @param prompt 图片描述文字（建议中文，越详细越好）
     * @return 生成图片的二进制数据（PNG 格式）
     * @throws Exception 网络错误、API 返回非 200、响应解析失败、图片下载失败等
     */
    public byte[] generateImage(String prompt) throws Exception {
        return generateImage(prompt, DEFAULT_IMAGE_SIZE, DEFAULT_IMAGE_N);
    }

    /**
     * 文生图：根据文字描述生成图片（可自定义参数）
     *
     * @param prompt 图片描述文字（建议中文，越详细越好）
     * @param size   图片尺寸，如 "1024*1024"、"768*1024"、"1024*768"、"1280*720"
     * @param n      生成图片数量（建议 1，多张图片会取第一张）
     * @return 生成图片的二进制数据
     * @throws Exception 网络错误、API 返回非 200、响应解析失败、图片下载失败等
     */
    public byte[] generateImage(String prompt, String size, int n) throws Exception {
        if (prompt == null || prompt.trim().isEmpty()) {
            throw new IllegalArgumentException("图片描述不能为空");
        }

        // ===== 同步调用：直接 POST 到 multimodal-generation =====
        // （不使用 X-DashScope-Async，因为部分 API Key 没有异步权限）
        //
        // POST https://dashscope.aliyuncs.com/api/v1/services/aigc/multimodal-generation/generation
        // BODY:
        // {
        //   "model": "wan2.6-t2i",
        //   "input": {
        //     "messages": [
        //       {
        //         "role": "user",
        //         "content": [{"text": "图片描述"}]
        //       }
        //     ]
        //   },
        //   "parameters": {
        //     "size": "1024*1024",
        //     "n": 1,
        //     "prompt_extend": true,
        //     "watermark": false
        //   }
        // }
        //
        // 响应示例（同步模式）：
        // {
        //   "output": {
        //     "choices": [...],
        //     "result": [{"url": "https://dashscope-result.../image.png"}]
        //   },
        //   "usage": {...},
        //   "request_id": "..."
        // }

        String body = "{"
            + "\"model\":\"" + IMAGE_MODEL + "\","
            + "\"input\":{"
            +     "\"messages\":["
            +         "{"
            +             "\"role\":\"user\","
            +             "\"content\":["
            +                 "{\"text\":" + jsonEscape(prompt) + "}"
            +             "]"
            +         "}"
            +     "]"
            +   "},"
            + "\"parameters\":{"
            +     "\"size\":" + jsonEscape(size) + ","
            +     "\"n\":" + n + ","
            +     "\"prompt_extend\":true,"
            +     "\"watermark\":false"
            +   "}"
            + "}";

        System.out.println("  [DashScope] 正在提交文生图请求...");

        HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create(MULTIMODAL_ENDPOINT))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer " + apiKey)
            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
            .build();

        long startTime = System.currentTimeMillis();
        HttpResponse<String> resp = http.send(req,
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

        if (resp.statusCode() != 200) {
            String bodyPreview = resp.body();
            if (bodyPreview.length() > 300) bodyPreview = bodyPreview.substring(0, 300) + "...";
            throw new Exception("文生图请求失败 HTTP " + resp.statusCode() + ": " + bodyPreview);
        }

        long elapsed = System.currentTimeMillis() - startTime;
        System.out.println("  [DashScope] 请求完成（耗时 " + elapsed / 1000.0 + " 秒）");

        // ===== 从响应中提取图片 URL =====
        String imageUrl = extractImageUrl(resp.body());
        if (imageUrl == null || imageUrl.isEmpty()) {
            // 可能是图片还没生成完，或者格式不同，尝试其他方式
            String errMsg = extractErrorMessage(resp.body());
            if (errMsg != null && !errMsg.trim().isEmpty()) {
                throw new Exception("文生图 API 错误: " + errMsg);
            }
            throw new Exception("无法从响应中提取图片 URL: " + resp.body());
        }

        System.out.println("  [DashScope] 下载生成的图片: " + (imageUrl.length() > 80 ? imageUrl.substring(0, 80) + "..." : imageUrl));

        // ===== 下载图片 =====
        HttpRequest downloadReq = HttpRequest.newBuilder()
            .uri(URI.create(imageUrl))
            .header("User-Agent", "Mozilla/5.0")
            .GET()
            .build();

        HttpResponse<byte[]> downloadResp = http.send(downloadReq,
            HttpResponse.BodyHandlers.ofByteArray());

        if (downloadResp.statusCode() != 200 || downloadResp.body() == null || downloadResp.body().length == 0) {
            throw new Exception("图片下载失败: HTTP " + downloadResp.statusCode());
        }

        return downloadResp.body();
    }

    // ============================================================
    // 以下是 JSON 辅助方法（与 SimpleBot 保持一致的风格）
    // ============================================================

    /** JSON 字符串转义 */
    private static String jsonEscape(String s) {
        if (s == null) return "\"\"";
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':  sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        sb.append("\"");
        return sb.toString();
    }

    /**
     * 从 JSON 响应中提取 content 字段（用于图片理解）
     *
     * 响应格式：
     * {
     *   "output": {
     *     "choices": [
     *       { "message": { "content": "要提取的文本" } }
     *     ]
     *   }
     * }
     */
    private static String extractContent(String json) {
        // 响应格式：
        //   "content": "文本字符串"              ← 格式1
        //   "content": [{"text": "文本"}]        ← 格式2（视觉模型常用）

        // 找到最后一个 "content" 字段位置
        int idx = json.lastIndexOf("\"content\"");
        if (idx < 0) return null;

        int colon = json.indexOf(":", idx);
        if (colon < 0) return null;

        // 跳过冒号后的空白
        int p = colon + 1;
        while (p < json.length() && (json.charAt(p) == ' ' || json.charAt(p) == '\t')) p++;
        if (p >= json.length()) return null;

        // ===== 情况1：content 是字符串 "..." =====
        if (json.charAt(p) == '"') {
            int q1 = p;
            int q2 = q1 + 1;
            StringBuilder sb = new StringBuilder();
            boolean escape = false;
            while (q2 < json.length()) {
                char c = json.charAt(q2);
                if (escape) {
                    switch (c) {
                        case 'n':  sb.append('\n'); break;
                        case 't':  sb.append('\t'); break;
                        case 'r':  sb.append('\r'); break;
                        case '"':  sb.append('"'); break;
                        case '\\': sb.append('\\'); break;
                        case '/':  sb.append('/'); break;
                        default:   sb.append(c);
                    }
                    escape = false;
                } else if (c == '\\') {
                    escape = true;
                } else if (c == '"') {
                    String result = sb.toString();
                    // 如果返回的只是 "text"（即把 key 名当内容了），说明是数组格式，尝试下面
                    if (result.equals("text")) {
                        // fall through 到数组解析
                    } else if (result.length() > 0) {
                        return result;
                    }
                    break;
                } else {
                    sb.append(c);
                }
                q2++;
            }
        }

        // ===== 情况2：content 是数组 [{"text": "..."}] =====
        // 从 content 字段位置开始，找 "text": "..." 的模式，取最后一个
        int textIdx = json.lastIndexOf("\"text\"");
        if (textIdx >= 0) {
            int textColon = json.indexOf(":", textIdx);
            if (textColon >= 0) {
                int textQ1 = json.indexOf("\"", textColon + 1);
                if (textQ1 >= 0) {
                    int q2 = textQ1 + 1;
                    StringBuilder sb = new StringBuilder();
                    boolean escape = false;
                    while (q2 < json.length()) {
                        char c = json.charAt(q2);
                        if (escape) {
                            switch (c) {
                                case 'n':  sb.append('\n'); break;
                                case 't':  sb.append('\t'); break;
                                case 'r':  sb.append('\r'); break;
                                case '"':  sb.append('"'); break;
                                case '\\': sb.append('\\'); break;
                                case '/':  sb.append('/'); break;
                                default:   sb.append(c);
                            }
                            escape = false;
                        } else if (c == '\\') {
                            escape = true;
                        } else if (c == '"') {
                            if (sb.length() > 0 && !sb.toString().equals("text")) {
                                return sb.toString();
                            }
                            break;
                        } else {
                            sb.append(c);
                        }
                        q2++;
                    }
                }
            }
        }

        return null;
    }

    /** 从响应中提取 task_id */
    private static String extractTaskId(String json) {
        int idx = json.indexOf("\"task_id\"");
        if (idx < 0) return null;
        int colon = json.indexOf(":", idx);
        if (colon < 0) return null;
        int q1 = json.indexOf("\"", colon + 1);
        if (q1 < 0) return null;
        int q2 = json.indexOf("\"", q1 + 1);
        if (q2 < 0) return null;
        return json.substring(q1 + 1, q2);
    }

    /** 从响应中提取 task_status */
    private static String extractTaskStatus(String json) {
        int idx = json.indexOf("\"task_status\"");
        if (idx < 0) return null;
        int colon = json.indexOf(":", idx);
        if (colon < 0) return null;
        int q1 = json.indexOf("\"", colon + 1);
        if (q1 < 0) return null;
        int q2 = json.indexOf("\"", q1 + 1);
        if (q2 < 0) return null;
        return json.substring(q1 + 1, q2);
    }

    /** 从响应中提取图片 URL */
    private static String extractImageUrl(String json) {
        // 先找 "result": [{"url": "..."}] 格式
        int idx = json.indexOf("\"result\"");
        if (idx >= 0) {
            int urlIdx = json.indexOf("\"url\"", idx);
            if (urlIdx >= 0) {
                int colon = json.indexOf(":", urlIdx);
                if (colon >= 0) {
                    int q1 = json.indexOf("\"", colon + 1);
                    if (q1 >= 0) {
                        int q2 = json.indexOf("\"", q1 + 1);
                        if (q2 >= 0) {
                            return json.substring(q1 + 1, q2);
                        }
                    }
                }
            }
        }

        // 备选：直接找第一个 https://*.png 之类的 URL
        int httpsIdx = json.indexOf("https://");
        if (httpsIdx >= 0) {
            int endIdx = json.indexOf("\"", httpsIdx);
            if (endIdx > httpsIdx) {
                String url = json.substring(httpsIdx, endIdx);
                if (url.contains(".png") || url.contains(".jpg") || url.contains(".jpeg") || url.contains("dashscope")) {
                    return url;
                }
            }
        }

        return null;
    }

    /** 从响应中提取 error / message 错误信息 */
    private static String extractErrorMessage(String json) {
        int idx = json.indexOf("\"message\"");
        if (idx < 0) idx = json.indexOf("\"msg\"");
        if (idx < 0) idx = json.indexOf("\"error\"");
        if (idx < 0) return null;

        int colon = json.indexOf(":", idx);
        if (colon < 0) return null;

        // 跳过空格
        int p = colon + 1;
        while (p < json.length() && (json.charAt(p) == ' ' || json.charAt(p) == '\t')) p++;

        // 如果是字符串，解析之
        if (p < json.length() && json.charAt(p) == '"') {
            int q1 = p;
            int q2 = q1 + 1;
            StringBuilder sb = new StringBuilder();
            boolean escape = false;
            while (q2 < json.length()) {
                char c = json.charAt(q2);
                if (escape) {
                    switch (c) {
                        case 'n': sb.append('\n'); break;
                        case 't': sb.append('\t'); break;
                        case 'r': sb.append('\r'); break;
                        case '"': sb.append('"'); break;
                        case '\\': sb.append('\\'); break;
                        default: sb.append(c);
                    }
                    escape = false;
                } else if (c == '\\') {
                    escape = true;
                } else if (c == '"') {
                    return sb.toString();
                } else {
                    sb.append(c);
                }
                q2++;
            }
        }

        // 不是字符串，简单取一个片段
        int end = Math.min(idx + 100, json.length());
        return json.substring(idx, end);
    }
}