package com.xwc.demo.wechat.iLink.demo;

import com.xwc.demo.wechat.iLink.ILinkClient;
import com.xwc.demo.wechat.iLink.core.config.ILinkConfig;
import com.xwc.demo.wechat.iLink.core.listener.OnMessageListener;
import com.xwc.demo.wechat.iLink.core.model.MessageItem;
import com.xwc.demo.wechat.iLink.core.model.WeixinMessage;
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
import java.util.Base64;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
import java.util.regex.Matcher;

public class WeChatBot {

    private static ILinkClient client;
    private static final AtomicBoolean running = new AtomicBoolean(true);
    private static boolean autoReplyEnabled = true;

    // ==================== LLM 大模型配置（文本对话）====================
    private static String llmBaseUrl = "";
    private static String llmApiKey = "";
    private static String llmModel = "";
    private static final String llmSystemPrompt = "你是一个友善、幽默、智能的微信聊天助手，用中文简短回复。";
    private static final Map<String, List<LlmMsg>> userMemoryObj = new ConcurrentHashMap<>();
    private static final int MEMORY_SIZE = 10;

    // ==================== 视觉模型配置（读图片理解）====================
    private static String visionBaseUrl = "";
    private static String visionApiKey = "";
    private static String visionModel = "";

    // ==================== 图片生成配置 ====================
    private static boolean imagegenEnabled = false;
    private static String imagegenBaseUrl = "";
    private static String imagegenApiKey = "";
    private static String imagegenModel = "";
    private static String imageSize = "1024*1024";
    private static int imageN = 1;

    // 图片生成关键词匹配模式
    private static final Pattern IMAGE_GEN_PATTERN = Pattern.compile(
        "(?:帮我?|请|可以)?(?:来|给|帮我?)?(画|绘制|生成|创作|造|draw|paint|create|generate|make)(?:一张|一幅|一个|张|幅|个|下|一下)?" +
        ".{0,15}(?:图|画|片|图片|image|picture|photo|pic|插画|漫画)|" +  // 带"图/画/片"的
        "(?:画|绘制|生成|创作|draw|paint|create|generate)(?:一只|一个|张|幅|下)?.{0,20}",  // 不带"图"的，如"画一只猫"
        Pattern.CASE_INSENSITIVE
    );

    private static final ObjectMapper objectMapper = new ObjectMapper();

    // ==================== 入口 ====================

    public static void main(String[] args) {
        System.out.println("========================================");
        System.out.println("  微信 iLink Bot - 大模型版");
        System.out.println("  支持: 智能聊天 | 多轮对话 | 图片理解 | 图片生成");
        System.out.println("========================================");

        loadConfig();

        ILinkConfig config = ILinkConfig.builder()
                .connectTimeoutMs(30000)
                .readTimeoutMs(30000)
                .writeTimeoutMs(30000)
                .httpMaxRetries(3)
                .heartbeatEnabled(true)
                .heartbeatIntervalMs(30000)
                .build();

        client = ILinkClient.builder()
                .config(config)
                .onMessage(new OnMessageListener() {
                    @Override
                    public void onMessages(List<WeixinMessage> messages) {
                        for (WeixinMessage msg : messages) {
                            String from = msg.getFrom_user_id();
                            if (from == null) continue;

                            // 提取消息内容
                            String text = extractText(msg);
                            boolean hasImage = hasImage(msg);
                            boolean hasVoice = hasVoice(msg);
                            boolean hasVideo = hasVideo(msg);
                            boolean hasFile = hasFile(msg);

                            System.out.println("\n[收到] " + from);
                            if (text != null) System.out.println("  文本: " + text);
                            if (hasImage) System.out.println("  [图片]");
                            if (hasVoice) System.out.println("  [语音]");
                            if (hasVideo) System.out.println("  [视频]");
                            if (hasFile) System.out.println("  [文件]");

                            if (autoReplyEnabled) {
                                try {
                                    handleAndReply(from, msg, text, hasImage, hasVoice, hasVideo, hasFile);
                                } catch (Exception e) {
                                    System.out.println("  [处理异常] " + e.getMessage());
                                    try {
                                        client.sendText(from, "处理消息时出错了: " + e.getMessage());
                                    } catch (Exception ignored) {}
                                }
                            }
                        }
                    }
                })
                .build();

        try {
            System.out.println("[登录] 正在获取微信二维码...");
            String qrContent = client.executeLogin();
            saveQrCodePage(qrContent);
            openQrCodeInBrowser();
            System.out.println("[登录] ✅ 扫码完成，已登录");
        } catch (Exception e) {
            System.err.println("[登录] ❌ 失败: " + e.getMessage());
            e.printStackTrace();
            return;
        }

        System.out.println("\n✅ 机器人已启动。输入 help 查看命令");
        System.out.println("💡 小技巧: 输入 test-llm 可以单独测试大模型 API 是否通");
        printHelp();

        Scanner scanner = new Scanner(System.in);
        while (running.get()) {
            try {
                System.out.print("> ");
                if (!scanner.hasNextLine()) break;
                String line = scanner.nextLine().trim();
                if (line.isEmpty()) continue;

                if (line.equalsIgnoreCase("exit") || line.equalsIgnoreCase("quit")) {
                    running.set(false);
                    break;
                } else if (line.equalsIgnoreCase("help")) {
                    printHelp();
                } else if (line.equalsIgnoreCase("status")) {
                    System.out.println("[状态] 登录: " + (client != null && client.isLoggedIn()) +
                                      " | 自动回复: " + autoReplyEnabled +
                                      " | 图片生成: " + (imagegenEnabled ? "已启用" : "未启用"));
                } else if (line.equalsIgnoreCase("on")) {
                    autoReplyEnabled = true;
                    System.out.println("[已开启自动回复]");
                } else if (line.equalsIgnoreCase("off")) {
                    autoReplyEnabled = false;
                    System.out.println("[已关闭自动回复]");
                } else if (line.equalsIgnoreCase("clear")) {
                    userMemoryObj.clear();
                    System.out.println("[所有对话记忆已清空]");
                } else if (line.equalsIgnoreCase("test-llm")) {
                    System.out.println("\n---- 开始测试 LLM API ----");
                    if (!isLlmEnabled()) {
                        System.out.println("❌ 配置不完整（base-url / api-key / model 有缺失）");
                        continue;
                    }
                    try {
                        String reply = callLlm("_test_user", "你好，请用不超过10个字回复");
                        System.out.println("✅ 大模型回复: " + reply);
                    } catch (Exception e) {
                        System.out.println("❌ 调用失败: " + e.getClass().getSimpleName() + ": " + e.getMessage());
                    }
                    System.out.println("---- 测试结束 ----\n");
                } else if (line.startsWith("send ")) {
                    String[] parts = line.split(" ", 3);
                    if (parts.length >= 3) {
                        client.sendText(parts[1], parts[2]);
                        System.out.println("[已发送]");
                    } else {
                        System.out.println("用法: send <ID> <消息内容>");
                    }
                } else {
                    System.out.println("未知命令。输入 help 查看可用命令。");
                }
            } catch (Exception e) {
                System.err.println("[错误] " + e.getMessage());
            }
        }

        try {
            if (client != null) client.close();
        } catch (Exception ignored) {}
        scanner.close();
        System.out.println("\nBot 已关闭");
    }

    // ==================== 消息处理与回复 ====================

    private static void handleAndReply(String from, WeixinMessage msg, String text,
                                       boolean hasImage, boolean hasVoice,
                                       boolean hasVideo, boolean hasFile) throws Exception {
        String reply;

        // 1. 处理图片消息（读图片理解）
        if (hasImage) {
            reply = handleImageMessage(from, msg, text);
            client.sendText(from, reply);
            return;
        }

        // 2. 处理其他非文本消息类型
        if (hasVoice) { client.sendText(from, "（我听到你发了语音，但目前只能理解文字~ 打字告诉我吧）"); return; }
        if (hasVideo) { client.sendText(from, "（我看到视频，但目前只能理解文字~ 打字告诉我你想聊什么吧）"); return; }
        if (hasFile) { client.sendText(from, "（我看到文件，但目前只能理解文字~ 打字告诉我吧）"); return; }

        // 3. 纯文本消息
        if (text == null) {
            client.sendText(from, "你好呀~ 有什么事可以用文字告诉我吗？");
            return;
        }

        String t = text.trim();
        String tLow = t.toLowerCase();

        // 特殊指令：重置对话记忆
        if (tLow.equals("重置对话") || tLow.equals("清空记忆") || tLow.equals("/reset") || tLow.equalsIgnoreCase("clear")) {
            userMemoryObj.remove(from);
            client.sendText(from, "好的，我们重新开始聊天吧~");
            return;
        }

        // 4. 检测是否是图片生成请求
        if (isImageGenerationRequest(t)) {
            handleImageGeneration(from, t);
            return;
        }

        // 5. 普通文本对话
        if (!isLlmEnabled()) {
            client.sendText(from, "我还没配置大模型呢~ 请在 config.properties 里设置 llm.base-url、llm.api-key 和 llm.model，然后重启。");
            return;
        }

        long t0 = System.currentTimeMillis();
        System.out.println("  [LLM] 正在调用 " + llmModel + " ...");
        try {
            String llmReply = callLlm(from, t);
            System.out.println("  [LLM] 回复完成（" + (System.currentTimeMillis() - t0) + " ms，" + llmReply.length() + " 字）");
            client.sendText(from, llmReply);
        } catch (Exception e) {
            String errMsg = e.getMessage();
            if (errMsg != null && errMsg.length() > 200) errMsg = errMsg.substring(0, 200) + "...";
            System.out.println("  [LLM] 调用失败: " + errMsg);
            client.sendText(from, "（大模型暂时没响应，请稍后再试。详细错误已打印到控制台）");
        }
    }

    // ==================== 图片理解（读图片） ====================

    private static String handleImageMessage(String from, WeixinMessage msg, String text) throws Exception {
        System.out.println("  [图片理解] 正在下载并分析图片...");

        try {
            // 下载图片
            byte[] imageData = downloadUserImage(msg);

            if (imageData == null || imageData.length == 0) {
                return "（图片下载失败了，请再发一次试试）";
            }

            System.out.println("  [图片理解] 下载成功，大小: " + imageData.length / 1024 + " KB");

            // 转 base64
            String base64Image = Base64.getEncoder().encodeToString(imageData);
            String mimeType = detectMimeType(imageData);

            // 构建用户的问题
            String userQuestion = (text != null && !text.trim().isEmpty())
                                  ? text.trim()
                                  : "这张图片里有什么？请描述一下内容";

            // 调用支持视觉的大模型
            String analysis = callLlmWithImage(from, base64Image, mimeType, userQuestion);
            return analysis;

        } catch (Exception e) {
            System.err.println("  [图片理解] 错误: " + e.getMessage());
            e.printStackTrace();
            return "（分析图片时出错了: " + e.getMessage() + "）";
        }
    }

    private static byte[] downloadUserImage(WeixinMessage msg) throws Exception {
        if (msg.getItem_list() == null) return null;
        for (MessageItem item : msg.getItem_list()) {
            if (item.getImage_item() != null && item.getImage_item().getMedia() != null) {
                return client.downloadImageFromMessageItem(item);
            }
        }
        return null;
    }

    private static String detectMimeType(byte[] data) {
        if (data == null || data.length < 8) return "image/jpeg";

        // 通过 magic bytes 判断图片格式
        if (data[0] == (byte)0x89 && data[1] == 0x50 && data[2] == 0x47 && data[3] == 0x4E) {
            return "image/png"; // PNG
        }
        if (data[0] == (byte)0xFF && data[1] == (byte)0xD8) {
            return "image/jpeg"; // JPEG
        }
        if (data[0] == 0x47 && data[1] == 0x49 && data[2] == 0x46) {
            return "image/gif"; // GIF
        }
        if (data[0] == 0x52 && data[1] == 0x49 && data[2] == 0x46 && data[3] == 0x46) {
            return "image/webp"; // WebP
        }

        return "image/jpeg"; // 默认
    }

    private static String callLlmWithImage(String userId, String base64Image, String mimeType, String question) throws Exception {
        List<Map<String, Object>> messages = new ArrayList<>();

        // system prompt
        Map<String, Object> systemMsg = new LinkedHashMap<>();
        systemMsg.put("role", "system");
        systemMsg.put("content", "你是一个智能图像分析师。当用户发给你图片时，仔细观察并用中文描述图片内容。" +
                          "如果用户有具体问题，请针对问题回答。回答要简洁明了。");
        messages.add(systemMsg);

        // 用户消息（包含图片）
        Map<String, Object> userMsg = new LinkedHashMap<>();
        List<Map<String, Object>> contentList = new ArrayList<>();

        // 文本部分
        Map<String, Object> textPart = new LinkedHashMap<>();
        textPart.put("type", "text");
        textPart.put("text", question);
        contentList.add(textPart);

        // 图片部分
        Map<String, Object> imagePart = new LinkedHashMap<>();
        imagePart.put("type", "image_url");
        Map<String, String> imageUrl = new HashMap<>();
        imageUrl.put("url", "data:" + mimeType + ";base64," + base64Image);
        imagePart.put("image_url", imageUrl);
        contentList.add(imagePart);

        userMsg.put("role", "user");
        userMsg.put("content", contentList);
        messages.add(userMsg);

        // 构建请求体（使用视觉模型配置）
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", visionModel);
        body.put("messages", messages);
        body.put("max_tokens", 1500);
        String jsonBody = objectMapper.writeValueAsString(body);

        // 发送请求到视觉模型 API
        String endpoint = visionBaseUrl.endsWith("/") ? visionBaseUrl + "chat/completions" : visionBaseUrl + "/chat/completions";
        System.out.println("  [图片理解] POST " + endpoint + "  | model=" + visionModel + "  | 图片大小=" + (base64Image.length() / 1024) + "KB");

        URL url = URI.create(endpoint).toURL();
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(30000); // 图片分析需要更长超时
        conn.setReadTimeout(120000);
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        conn.setRequestProperty("Authorization", "Bearer " + visionApiKey);
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

        System.out.println("  [图片理解] 响应: HTTP " + code + "  | " + resp.length() + " 字符");

        if (code < 200 || code >= 300) {
            throw new Exception("HTTP " + code + ": " + resp);
        }

        JsonNode root = objectMapper.readTree(resp.toString());
        JsonNode choices = root.path("choices");
        if (choices.isArray() && choices.size() > 0) {
            String reply = choices.get(0).path("message").path("content").asText("");
            if (!reply.isEmpty()) {
                return reply;
            }
        }
        throw new Exception("响应格式异常: " + (resp.length() > 400 ? resp.substring(0, 400) + "..." : resp));
    }

    // ==================== 图片生成 ====================

    private static boolean isImageGenerationRequest(String text) {
        if (!imagegenEnabled) return false;
        Matcher matcher = IMAGE_GEN_PATTERN.matcher(text);
        return matcher.find();
    }

    private static void handleImageGeneration(String from, String prompt) throws Exception {
        System.out.println("  [图片生成] 正在生成图片... 提示词: " + prompt);

        try {
            byte[] imageData = generateImage(prompt);

            if (imageData == null || imageData.length == 0) {
                client.sendText(from, "（图片生成失败了，请换个描述再试一次）");
                return;
            }

            System.out.println("  [图片生成] 生成成功，大小: " + imageData.length / 1024 + " KB");

            // 发送图片给用户
            String fileName = "generated_" + System.currentTimeMillis() + ".png";
            client.sendImage(from, imageData, fileName, "为你生成的图片 ✨");

            System.out.println("  [图片生成] 已发送给用户");

        } catch (Exception e) {
            System.err.println("  [图片生成] 错误: " + e.getMessage());
            e.printStackTrace();
            client.sendText(from, "（生成图片时出错了: " + e.getMessage() + "）");
        }
    }

    private static byte[] generateImage(String prompt) throws Exception {
        // ========== 多种请求体格式 ==========

        // 格式 A: {model, input:{prompt}, parameters:{size, n}}  (旧版 text2image)
        Map<String, Object> inputA = new LinkedHashMap<>();
        inputA.put("prompt", prompt);
        Map<String, Object> paramsA = new LinkedHashMap<>();
        paramsA.put("size", imageSize);
        paramsA.put("n", imageN);
        Map<String, Object> bodyA = new LinkedHashMap<>();
        bodyA.put("model", imagegenModel);
        bodyA.put("input", inputA);
        bodyA.put("parameters", paramsA);
        String bodyAJson = objectMapper.writeValueAsString(bodyA);

        // 格式 B: {model, prompt, size, n}  (OpenAI兼容)
        Map<String, Object> bodyB = new LinkedHashMap<>();
        bodyB.put("model", imagegenModel);
        bodyB.put("prompt", prompt);
        bodyB.put("size", imageSize.replace('*', 'x'));
        bodyB.put("n", imageN);
        String bodyBJson = objectMapper.writeValueAsString(bodyB);

        // 格式 C: {model, input:{messages:[{role:user, content:prompt}]}, parameters:{size, n}}
        //         (新版 multimodal 消息格式)
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("role", "user");
        msg.put("content", prompt);
        Map<String, Object> inputC = new LinkedHashMap<>();
        inputC.put("messages", new Object[]{msg});
        Map<String, Object> paramsC = new LinkedHashMap<>();
        paramsC.put("size", imageSize.replace('*', 'x'));
        paramsC.put("n", imageN);
        Map<String, Object> bodyC = new LinkedHashMap<>();
        bodyC.put("model", imagegenModel);
        bodyC.put("input", inputC);
        bodyC.put("parameters", paramsC);
        String bodyCJson = objectMapper.writeValueAsString(bodyC);

        // 格式 D: 同 C 但 size 用 1024*1024 (不转 x)
        Map<String, Object> paramsD = new LinkedHashMap<>();
        paramsD.put("size", imageSize);
        paramsD.put("n", imageN);
        Map<String, Object> bodyD = new LinkedHashMap<>();
        bodyD.put("model", imagegenModel);
        bodyD.put("input", inputC);
        bodyD.put("parameters", paramsD);
        String bodyDJson = objectMapper.writeValueAsString(bodyD);

        // 格式 E: content 是数组 [{type:"text", text:"..."}]  (新版多模态)
        //       {model, input:{messages:[{role:"user", content:[{type:"text", text:"..."}]}]}, parameters:{size:"1024*1024", n:1}}
        Map<String, Object> textItem = new LinkedHashMap<>();
        textItem.put("type", "text");
        textItem.put("text", prompt);
        Object[] contentArray = new Object[]{textItem};
        Map<String, Object> msgE = new LinkedHashMap<>();
        msgE.put("role", "user");
        msgE.put("content", contentArray);
        Map<String, Object> inputE = new LinkedHashMap<>();
        inputE.put("messages", new Object[]{msgE});
        Map<String, Object> paramsE = new LinkedHashMap<>();
        paramsE.put("size", imageSize);
        paramsE.put("n", imageN);
        Map<String, Object> bodyE = new LinkedHashMap<>();
        bodyE.put("model", imagegenModel);
        bodyE.put("input", inputE);
        bodyE.put("parameters", paramsE);
        String bodyEJson = objectMapper.writeValueAsString(bodyE);

        // ========== 候选 URL 列表 ==========
        String[] urls = new String[]{
            "https://dashscope.aliyuncs.com/api/v1/services/aigc/text2image/image-synthesis",
            "https://dashscope.aliyuncs.com/compatible-mode/v1/images/generations",
            "https://dashscope.aliyuncs.com/api/v1/services/aigc/multimodal-generation/generation",
        };

        // 候选请求体列表：[body, 描述]
        String[][] bodies = new String[][]{
            {bodyAJson, "DashScope嵌套prompt"},
            {bodyBJson, "OpenAI扁平"},
            {bodyCJson, "消息格式size=x"},
            {bodyDJson, "消息格式size=*"},
            {bodyEJson, "消息格式content数组"},
        };

        // ========== 遍历：每个 URL × 每个请求体 ==========
        Exception lastError = null;
        for (String url : urls) {
            for (String[] bd : bodies) {
                String body = bd[0];
                String fmt = bd[1];

                System.out.println("  [图片生成] 尝试: POST " + url + " | model=" + imagegenModel + " | 格式=" + fmt);
                System.out.println("  [图片生成] 请求体: " + body);

                try {
                    URL u = URI.create(url).toURL();
                    HttpURLConnection conn = (HttpURLConnection) u.openConnection();
                    conn.setConnectTimeout(30000);
                    conn.setReadTimeout(120000);
                    conn.setRequestMethod("POST");
                    conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                    conn.setRequestProperty("Authorization", "Bearer " + imagegenApiKey);
                    conn.setDoOutput(true);

                    try (OutputStream os = conn.getOutputStream()) {
                        os.write(body.getBytes(StandardCharsets.UTF_8));
                    }

                    int code = conn.getResponseCode();
                    StringBuilder resp = new StringBuilder();
                    InputStream is = (code >= 400) ? conn.getErrorStream() : conn.getInputStream();
                    if (is != null) {
                        try (BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                            String line;
                            while ((line = br.readLine()) != null) resp.append(line);
                        }
                    }

                    System.out.println("  [图片生成] 响应: HTTP " + code + " | " + resp.length() + " 字符");

                    if (code < 200 || code >= 300) {
                        String errText = resp.length() > 0 ? resp.toString() : "(响应流为空)";
                        System.out.println("  [图片生成] 错误详情: " + errText);
                        lastError = new Exception("HTTP " + code + ": " + errText);
                        continue;
                    }

                    // ====== 解析响应 ======
                    JsonNode root = objectMapper.readTree(resp.toString());

                    // 格式 1: data[0].url / data[0].b64_json
                    JsonNode data = root.path("data");
                    if (data.isArray() && data.size() > 0) {
                        JsonNode first = data.get(0);
                        if (first.has("url")) {
                            String imageUrl = first.get("url").asText();
                            System.out.println("  [图片生成] ✅ 图片URL: " + imageUrl.substring(0, Math.min(120, imageUrl.length())) + "...");
                            return downloadImageFromUrl(imageUrl);
                        }
                        if (first.has("b64_json")) {
                            String b64 = first.get("b64_json").asText("");
                            return Base64.getDecoder().decode(b64);
                        }
                    }

                    // 格式 2: output.results[0].url / b64_image
                    JsonNode output = root.path("output");
                    if (output.has("results") && output.get("results").isArray()) {
                        JsonNode first = output.get("results").get(0);
                        if (first.has("url")) {
                            String imageUrl = first.get("url").asText();
                            System.out.println("  [图片生成] ✅ 图片URL: " + imageUrl.substring(0, Math.min(120, imageUrl.length())) + "...");
                            return downloadImageFromUrl(imageUrl);
                        }
                        if (first.has("b64_image")) {
                            String b64 = first.get("b64_image").asText("");
                            return Base64.getDecoder().decode(b64);
                        }
                    }

                    // 格式 3: output.url（直接返回 URL）
                    if (output.has("url")) {
                        String imageUrl = output.get("url").asText();
                        System.out.println("  [图片生成] ✅ 图片URL: " + imageUrl.substring(0, Math.min(120, imageUrl.length())) + "...");
                        return downloadImageFromUrl(imageUrl);
                    }

                    // 格式 4: 顶层 url
                    if (root.has("url")) {
                        return downloadImageFromUrl(root.get("url").asText());
                    }

                    // 格式 5: output.choices[0].message.content[0].image  (新版 multimodal)
                    JsonNode choices = output.path("choices");
                    if (choices.isArray() && choices.size() > 0) {
                        JsonNode message = choices.get(0).path("message");
                        JsonNode contentArr = message.path("content");
                        if (contentArr.isArray() && contentArr.size() > 0) {
                            JsonNode firstItem = contentArr.get(0);
                            if (firstItem.has("image")) {
                                String imageUrl = firstItem.get("image").asText();
                                // URL 两端可能带反引号 ``，去掉
                                imageUrl = imageUrl.replaceAll("^`|`$", "").trim();
                                System.out.println("  [图片生成] ✅ 图片URL: " + imageUrl.substring(0, Math.min(120, imageUrl.length())) + "...");
                                return downloadImageFromUrl(imageUrl);
                            }
                        }
                    }

                    lastError = new Exception("HTTP 200 但解析失败，原始响应: " + (resp.length() > 400 ? resp.substring(0, 400) + "..." : resp));

                } catch (Exception e) {
                    lastError = e;
                    System.out.println("  [图片生成] 异常: " + e.getMessage());
                }
            }
        }

        // 所有组合都失败
        System.out.println("  [图片生成] ❌ 所有方式都失败，请换一个 model 试试，比如 wanx-v1");
        if (lastError != null) throw lastError;
        throw new Exception("图片生成失败，请尝试其他 model，如 wanx-v1");
    }

    private static byte[] downloadImageFromUrl(String urlString) throws Exception {
        URL url = new URL(urlString);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(60000);
        conn.setRequestMethod("GET");

        int code = conn.getResponseCode();
        if (code != 200) {
            throw new Exception("下载图片 HTTP " + code);
        }

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (InputStream is = conn.getInputStream()) {
            byte[] buffer = new byte[4096];
            int bytesRead;
            while ((bytesRead = is.read(buffer)) != -1) {
                baos.write(buffer, 0, bytesRead);
            }
        }
        return baos.toByteArray();
    }

    // ==================== LLM 文本对话 ====================

    private static boolean isLlmEnabled() {
        return !llmBaseUrl.isEmpty() && !llmApiKey.isEmpty() && !llmModel.isEmpty();
    }

    private static void loadConfig() {
        String loadedFrom = null;
        try {
            Properties props = new Properties();
            java.nio.file.Path p = Paths.get("config.properties");
            if (Files.exists(p)) {
                try (FileReader fr = new FileReader(p.toFile())) {
                    props.load(fr);
                }
                loadedFrom = p.toAbsolutePath().toString();
            } else {
                try (InputStream is = WeChatBot.class.getClassLoader().getResourceAsStream("config.properties")) {
                    if (is != null) {
                        props.load(is);
                        loadedFrom = "classpath:config.properties";
                    }
                }
            }

            // LLM 配置（文本对话）
            llmBaseUrl = trim(props.getProperty("llm.base-url", ""));
            llmApiKey  = trim(props.getProperty("llm.api-key", ""));
            llmModel   = trim(props.getProperty("llm.model", ""));

            // 视觉模型配置（读图片理解，默认复用 LLM 的 base-url 和 api-key）
            visionBaseUrl = trim(props.getProperty("vision.base-url", llmBaseUrl));
            visionApiKey  = trim(props.getProperty("vision.api-key", llmApiKey));
            visionModel   = trim(props.getProperty("vision.model", "qwen-vl-max"));

            // 图片生成配置
            imagegenEnabled = Boolean.parseBoolean(props.getProperty("imagegen.enabled", "false"));
            imagegenBaseUrl = trim(props.getProperty("imagegen.base-url", ""));
            imagegenApiKey   = trim(props.getProperty("imagegen.api-key", ""));
            if (imagegenApiKey.isEmpty()) {
                imagegenApiKey = llmApiKey; // 默认使用 LLM 的 key
            }
            imagegenModel = trim(props.getProperty("imagegen.model", ""));
            imageSize     = trim(props.getProperty("imagegen.size", "1024*1024"));
            imageN       = Integer.parseInt(props.getProperty("imagegen.n", "1"));

        } catch (Exception ignored) {}

        System.out.println("----------------------------------------");
        if (loadedFrom != null) {
            System.out.println("[配置] 从 " + loadedFrom + " 读取");
        } else {
            System.out.println("[配置] ❌ 没有找到 config.properties");
        }
        System.out.println("[LLM]  base-url = " + (llmBaseUrl.isEmpty() ? "（未设置）" : llmBaseUrl));
        System.out.println("[LLM]  api-key  = " + (llmApiKey.isEmpty() ? "（未设置）" : (llmApiKey.substring(0, Math.min(4, llmApiKey.length())) + "***")));
        System.out.println("[LLM]  model    = " + (llmModel.isEmpty() ? "（未设置）" : llmModel));
        System.out.println("[视觉] base-url = " + (visionBaseUrl.isEmpty() ? "（未设置，复用 LLM）" : visionBaseUrl));
        System.out.println("[视觉] api-key  = " + (visionApiKey.isEmpty() ? "（未设置）" : (visionApiKey.substring(0, Math.min(4, visionApiKey.length())) + "***")));
        System.out.println("[视觉] model    = " + (visionModel.isEmpty() ? "（未设置）" : visionModel));
        System.out.println("[图片生成] " + (imagegenEnabled ? "✅ 已启用" : "❌ 未启用") + " | model=" + imagegenModel);
        System.out.println("----------------------------------------");
    }

    private static String trim(String s) {
        return s == null ? "" : s.trim();
    }

    private static String callLlm(String userId, String userText) throws Exception {
        List<LlmMsg> history = userMemoryObj.computeIfAbsent(userId, k -> new ArrayList<>());
        history.add(new LlmMsg("user", userText));
        while (history.size() > MEMORY_SIZE * 2) history.remove(0);

        List<Map<String, Object>> messages = new ArrayList<>();
        Map<String, Object> systemMsg = new LinkedHashMap<>();
        systemMsg.put("role", "system");
        systemMsg.put("content", llmSystemPrompt);
        messages.add(systemMsg);

        for (LlmMsg entry : history) {
            Map<String, Object> msg = new LinkedHashMap<>();
            msg.put("role", entry.role);
            msg.put("content", entry.content);
            messages.add(msg);
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", llmModel);
        body.put("temperature", 0.7);
        body.put("max_tokens", 1000);
        body.put("messages", messages);
        String jsonBody = objectMapper.writeValueAsString(body);

        String endpoint = llmBaseUrl.endsWith("/") ? llmBaseUrl + "chat/completions" : llmBaseUrl + "/chat/completions";
        System.out.println("  [LLM] 请求: POST " + endpoint + "  | model=" + llmModel + "  | body=" +
                           jsonBody.length() + " 字符");

        URL url = URI.create(endpoint).toURL();
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(60000);
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        conn.setRequestProperty("Authorization", "Bearer " + llmApiKey);
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

        System.out.println("  [LLM] 响应: HTTP " + code + "  | " + resp.length() + " 字符");

        if (code < 200 || code >= 300) {
            throw new Exception("HTTP " + code + ": " + resp);
        }

        JsonNode root = objectMapper.readTree(resp.toString());
        JsonNode choices = root.path("choices");
        if (choices.isArray() && choices.size() > 0) {
            String reply = choices.get(0).path("message").path("content").asText("");
            if (!reply.isEmpty()) {
                history.add(new LlmMsg("assistant", reply));
                while (history.size() > MEMORY_SIZE * 2) history.remove(0);
                return reply;
            }
        }
        throw new Exception("响应格式异常: " + (resp.length() > 400 ? resp.substring(0, 400) + "..." : resp));
    }

    public static class LlmMsg {
        final String role;
        final String content;
        LlmMsg(String role, String content) { this.role = role; this.content = content; }
    }

    // ========== 工具方法 ==========

    private static String extractText(WeixinMessage msg) {
        if (msg.getItem_list() == null) return null;
        for (MessageItem item : msg.getItem_list()) {
            if (item.getText_item() != null) {
                return item.getText_item().getText();
            }
        }
        return null;
    }

    private static boolean hasImage(WeixinMessage msg) {
        if (msg.getItem_list() == null) return false;
        for (MessageItem item : msg.getItem_list()) {
            if (item.getImage_item() != null) return true;
        }
        return false;
    }

    private static boolean hasVoice(WeixinMessage msg) {
        if (msg.getItem_list() == null) return false;
        for (MessageItem item : msg.getItem_list()) {
            if (item.getVoice_item() != null) return true;
        }
        return false;
    }

    private static boolean hasVideo(WeixinMessage msg) {
        if (msg.getItem_list() == null) return false;
        for (MessageItem item : msg.getItem_list()) {
            if (item.getVideo_item() != null) return true;
        }
        return false;
    }

    private static boolean hasFile(WeixinMessage msg) {
        if (msg.getItem_list() == null) return false;
        for (MessageItem item : msg.getItem_list()) {
            if (item.getFile_item() != null) return true;
        }
        return false;
    }

    private static void printHelp() {
        System.out.println("\n========================================");
        System.out.println("  微信 iLink 智能 Bot");
        System.out.println("========================================");
        System.out.println("  功能: 大模型对话 | 多轮记忆 | 图片理解 | 图片生成");
        System.out.println("----------------------------------------");
        System.out.println("  控制台命令:");
        System.out.println("    help       查看帮助");
        System.out.println("    status     查看状态");
        System.out.println("    on/off     开启/关闭自动回复");
        System.out.println("    clear      清空对话记忆");
        System.out.println("    send <ID> <消息>  主动发消息");
        System.out.println("    test-llm   测试大模型连接");
        System.out.println("    exit       退出");
        System.out.println("----------------------------------------");
        System.out.println("  微信对话示例:");
        System.out.println("    直接发文字 → 大模型对话");
        System.out.println("    发图片 → 自动识别图片内容");
        System.out.println("    「画一只猫」「生成一张风景照」→ AI 生成图片");
        System.out.println("========================================\n");
    }

    // ========== 二维码相关 ==========

    private static void saveQrCodePage(String qrUrlOrContent) {
        try {
            String html;
            if (qrUrlOrContent != null && qrUrlOrContent.startsWith("http")) {
                html = "<!DOCTYPE html>\n" +
                        "<html>\n" +
                        "<head>\n" +
                        "    <meta charset=\"UTF-8\">\n" +
                        "    <title>微信扫码登录</title>\n" +
                        "    <meta http-equiv=\"refresh\" content=\"0;url=" + qrUrlOrContent + "\">\n" +
                        "    <style>\n" +
                        "        body { font-family: Arial, sans-serif; text-align: center; padding: 40px; background: #f5f5f5; }\n" +
                        "        .container { max-width: 600px; margin: 0 auto; background: white; padding: 40px; border-radius: 10px; box-shadow: 0 2px 10px rgba(0,0,0,0.1); }\n" +
                        "        h1 { color: #07c160; margin-bottom: 20px; }\n" +
                        "        a { display: inline-block; margin: 20px 0; padding: 12px 30px; background: #07c160; color: white; text-decoration: none; border-radius: 5px; font-size: 16px; }\n" +
                        "    </style>\n" +
                        "</head>\n" +
                        "<body>\n" +
                        "    <div class=\"container\">\n" +
                        "        <h1>微信扫码登录</h1>\n" +
                        "        <p>如果没有自动跳转，请点击下方按钮</p>\n" +
                        "        <a href=\"" + qrUrlOrContent + "\" target=\"_blank\">打开微信二维码页面</a>\n" +
                        "    </div>\n" +
                        "</body>\n" +
                        "</html>";
            } else {
                String imgSrc = qrUrlOrContent;
                if (imgSrc != null && !imgSrc.startsWith("data:image")) {
                    imgSrc = "data:image/png;base64," + imgSrc;
                }
                html = "<!DOCTYPE html><html><head><meta charset=\"UTF-8\"><title>登录</title></head><body style=\"text-align:center;padding:40px;\"><h1>扫码登录</h1><img src=\"" + (imgSrc == null ? "" : imgSrc) + "\" /></body></html>";
            }
            java.nio.file.Files.write(java.nio.file.Paths.get("qrcode.html"), html.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            System.err.println("[保存二维码失败] " + e.getMessage());
        }
    }

    private static void openQrCodeInBrowser() {
        try {
            File htmlFile = new File("qrcode.html");
            String absPath = htmlFile.getAbsolutePath();
            if (java.awt.Desktop.isDesktopSupported()) {
                java.awt.Desktop.getDesktop().browse(URI.create("file:///" + absPath.replace("\\", "/")));
            }
            System.out.println("[已在浏览器中打开二维码]");
        } catch (Exception e) {
            System.out.println("[提示] 请手动打开文件: qrcode.html");
        }
    }
}