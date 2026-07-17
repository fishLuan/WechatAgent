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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

public class WeChatBot {

    private static ILinkClient client;
    private static final AtomicBoolean running = new AtomicBoolean(true);
    private static boolean autoReplyEnabled = true;

    // ==================== LLM 大模型配置 ====================
    private static String llmBaseUrl = "";
    private static String llmApiKey = "";
    private static String llmModel = "";
    private static final String llmSystemPrompt = "你是一个友善、幽默、智能的微信聊天助手，用中文简短回复。";
    private static final Map<String, List<LlmMsg>> userMemoryObj = new ConcurrentHashMap<>();
    private static final int MEMORY_SIZE = 10;

    private static final ObjectMapper objectMapper = new ObjectMapper();

    // ==================== 入口 ====================

    public static void main(String[] args) {
        System.out.println("========================================");
        System.out.println("  微信 iLink Bot - 大模型版");
        System.out.println("  支持: 智能聊天 | 多轮对话");
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
                                String reply = generateReply(from, text, hasImage, hasVoice, hasVideo, hasFile);
                                System.out.println("  [回复] -> " + reply);
                                try {
                                    client.sendText(from, reply);
                                } catch (Exception e) {
                                    System.out.println("  [发送失败] " + e.getMessage());
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

        // 控制台交互
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
                    System.out.println("[状态] 登录: " + (client != null && client.isLoggedIn()) + " | 自动回复: " + autoReplyEnabled);
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
                    // 🔧 手动测试大模型 API 是否通
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
                        if (e.getMessage() != null && e.getMessage().contains("HTTP")) {
                            System.out.println("   → 说明网络请求有响应但报错了（401=密钥错, 404=路径错, 429=超限）");
                        } else {
                            System.out.println("   → 可能是网络不通或响应格式异常");
                        }
                    }
                    System.out.println("---- 测试结束 ----\n");
                } else if (line.startsWith("send ")) {
                    String[] parts = line.split(" ", 3);
                    if (parts.length >= 3) {
                        client.sendText(parts[1], parts[2]);
                        System.out.println("[已发送]");
                    } else {
                        System.out.println("用法: send <用户ID> <消息内容>");
                    }
                } else {
                    System.out.println("未知命令。输入 help 查看可用命令。");
                }
            } catch (Exception e) {
                System.err.println("[错误] " + e.getMessage());
            }
        }

        // 退出
        try {
            if (client != null) client.close();
        } catch (Exception ignored) {}
        scanner.close();
        System.out.println("\nBot 已关闭");
    }

    // ==================== 智能回复（只走大模型） ====================

    private static String generateReply(String from, String text, boolean hasImage, boolean hasVoice, boolean hasVideo, boolean hasFile) {
        if (hasImage) return "（我看到你发了一张图片，但目前只能理解文字~ 你可以用文字描述一下你想问什么）";
        if (hasVoice) return "（我听到你发了语音，但目前只能理解文字~ 打字告诉我吧）";
        if (hasVideo) return "（我看到视频，但目前只能理解文字~ 打字告诉我你想聊什么吧）";
        if (hasFile) return "（我看到文件，但目前只能理解文字~ 打字告诉我吧）";

        if (text == null) return "你好呀~ 有什么事可以用文字告诉我吗？";

        String t = text.trim();
        String tLow = t.toLowerCase();

        // 特殊指令：重置对话记忆
        if (tLow.equals("重置对话") || tLow.equals("清空记忆") || tLow.equals("/reset") || tLow.equalsIgnoreCase("clear")) {
            userMemoryObj.remove(from);
            return "好的，我们重新开始聊天吧~";
        }

        if (!isLlmEnabled()) {
            return "我还没配置大模型呢~ 请在 config.properties 里设置 llm.base-url、llm.api-key 和 llm.model，然后重启。";
        }

        long t0 = System.currentTimeMillis();
        System.out.println("  [LLM] 正在调用 " + llmModel + " ...");
        try {
            String reply = callLlm(from, t);
            System.out.println("  [LLM] 回复完成（" + (System.currentTimeMillis() - t0) + " ms，" + reply.length() + " 字）");
            return reply;
        } catch (Exception e) {
            String msg = e.getMessage();
            if (msg != null && msg.length() > 200) msg = msg.substring(0, 200) + "...";
            System.out.println("  [LLM] 调用失败: " + msg);
            return "（大模型暂时没响应，请稍后再试。详细错误已打印到控制台）";
        }
    }

    // ==================== LLM 大模型 ====================

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
            llmBaseUrl = trim(props.getProperty("llm.base-url", ""));
            llmApiKey  = trim(props.getProperty("llm.api-key", ""));
            llmModel   = trim(props.getProperty("llm.model", ""));
        } catch (Exception ignored) {}

        System.out.println("----------------------------------------");
        if (loadedFrom != null) {
            System.out.println("[配置] 从 " + loadedFrom + " 读取");
        } else {
            System.out.println("[配置] ❌ 没有找到 config.properties");
            System.out.println("[配置]   请将 config.properties 放在当前工作目录，或放到 src/main/resources/ 下");
        }
        System.out.println("[配置] llm.base-url = " + (llmBaseUrl.isEmpty() ? "（未设置）" : llmBaseUrl));
        System.out.println("[配置] llm.api-key  = " + (llmApiKey.isEmpty() ? "（未设置）" : (llmApiKey.substring(0, Math.min(4, llmApiKey.length())) + "***")));
        System.out.println("[配置] llm.model    = " + (llmModel.isEmpty() ? "（未设置）" : llmModel));
        System.out.println("----------------------------------------");
    }

    private static String trim(String s) {
        return s == null ? "" : s.trim();
    }

    private static String callLlm(String userId, String userText) throws Exception {
        // 维护该用户的对话记忆（最近 N 轮）—— 用自定义对象，避免用户输入里含冒号导致切分错误
        List<LlmMsg> history = userMemoryObj.computeIfAbsent(userId, k -> new ArrayList<>());
        history.add(new LlmMsg("user", userText));
        while (history.size() > MEMORY_SIZE * 2) history.remove(0);

        // 用 Jackson 构造请求体（避免用户输入里有引号等特殊字符的 JSON 注入问题）
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

        // HTTP POST
        String endpoint = llmBaseUrl.endsWith("/") ? llmBaseUrl + "chat/completions" : llmBaseUrl + "/chat/completions";
        System.out.println("  [LLM] 请求: POST " + endpoint + "  | model=" + llmModel + "  | body=" +
                           jsonBody.length() + " 字符（前 120: " + jsonBody.substring(0, Math.min(120, jsonBody.length())) + "）");

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
        if (code >= 400 && resp.length() > 0) {
            System.out.println("  [LLM] 错误体预览: " + resp.substring(0, Math.min(300, resp.length())));
        }

        if (code < 200 || code >= 300) {
            throw new Exception("HTTP " + code + ": " + resp);
        }

        // ⭐ 用 Jackson 解析响应：choices[0].message.content
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
        throw new Exception("响应格式异常（没有找到 choices[0].message.content）: " +
                           (resp.length() > 400 ? resp.substring(0, 400) + "..." : resp.toString()));
    }

    // 简化的数据类 —— 存多轮对话的一条消息
    private static class LlmMsg {
        final String role;
        final String content;
        LlmMsg(String role, String content) { this.role = role; this.content = content; }
    }

    // ==================== 本地关键词回退（已删除，只走大模型） ====================

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
        System.out.println("  功能: 大模型对话 | 多轮记忆");
        System.out.println("----------------------------------------");
        System.out.println("  控制台命令:");
        System.out.println("    help       查看帮助");
        System.out.println("    status     查看状态");
        System.out.println("    on/off     开启/关闭自动回复");
        System.out.println("    clear      清空对话记忆");
        System.out.println("    send <ID> <消息>  主动发消息");
        System.out.println("    exit       退出");
        System.out.println("----------------------------------------");
        System.out.println("  微信对话示例:");
        System.out.println("    直接给它发任何内容，让大模型给你回复");
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