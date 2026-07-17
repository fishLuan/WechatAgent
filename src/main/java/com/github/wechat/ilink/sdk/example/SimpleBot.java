package com.github.wechat.ilink.sdk.example;

import com.github.wechat.ilink.sdk.ILinkClient;
import com.github.wechat.ilink.sdk.core.listener.OnLoginListener;
import com.github.wechat.ilink.sdk.core.login.LoginContext;
import com.github.wechat.ilink.sdk.core.model.MessageItem;
import com.github.wechat.ilink.sdk.core.model.TextItem;
import com.github.wechat.ilink.sdk.core.model.WeixinMessage;

import java.awt.Desktop;
import java.io.File;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

public class SimpleBot {

    // ===== DeepSeek 配置 =====
    // ⚠️ 请勿将 API Key 硬编码在此处！
    // 从环境变量 DEEPSEEK_API_KEY 或系统属性 deepseek.api.key 读取
    // 配置方式：
    //   1. Win+R 输入: rundll32 sysdm.cpl,EditEnvironmentVariables
    //   2. 添加用户变量: DEEPSEEK_API_KEY = 你的key
    //   3. 重启 IDE
    private static final String DEEPSEEK_API_KEY =
        firstNonNull(System.getenv("DEEPSEEK_API_KEY"),
            System.getProperty("deepseek.api.key"),
            "");
    private static final String DEEPSEEK_MODEL = "deepseek-chat";
    private static final String DEEPSEEK_URL = "https://api.deepseek.com/v1/chat/completions";

    // ===== 阿里云百炼配置 =====
    // 从环境变量 DASHSCOPE_API_KEY 或系统属性 dashscope.api.key 读取
    // 提供功能：图片理解（看图）、文生图（画图）
    private static final String DASHSCOPE_API_KEY =
        firstNonNull(System.getenv("DASHSCOPE_API_KEY"),
            System.getProperty("dashscope.api.key"),
            "");
    private static final AliyunDashscopeService aliyunService =
        new AliyunDashscopeService(DASHSCOPE_API_KEY);

    // 人设
    private static final String SYSTEM_PROMPT =
        "你是一个友好、幽默、有耐心的微信机器人助手。"
        + "你的名字叫ClawBot。"
        + "用简洁自然的中文回答用户。"
        + "回答不要太长，控制在3句话以内。"
        + "不要讨论或编造你使用的底层技术、模型架构或API供应商。"
        + "当用户问你是什么模型时，用幽默的方式回答，比如'我是一个训练有素的语言模型小助手～'。";

    // 对话历史（维持上下文）
    private static final StringBuilder conversationHistory = new StringBuilder();

    // 已处理消息ID去重
    private static final Set<Long> processedMsgIds = new HashSet<>();

    private static String firstNonNull(String... items) {
        for (String s : items) {
            if (s != null && !s.trim().isEmpty()) return s;
        }
        return "";
    }

    public static void main(String[] args) {
        System.out.println();
        System.out.println("========================================");
        System.out.println("   WeChat iLink Bot - DeepSeek Edition");
        System.out.println("========================================");
        System.out.println();

        if (DEEPSEEK_API_KEY == null || DEEPSEEK_API_KEY.trim().isEmpty()) {
            System.out.println("[WARN] DeepSeek API Key 未配置，将使用 echo 模式");
            System.out.println("       请配置环境变量 DEEPSEEK_API_KEY 后重启");
            System.out.println();
        }

        if (!aliyunService.isConfigured()) {
            System.out.println("[WARN] 阿里云百炼 API Key 未配置，看图/画图功能不可用");
            System.out.println("       请配置环境变量 DASHSCOPE_API_KEY 后重启");
            System.out.println();
        }

        try {
            // ===== 1. 构建客户端 =====
            System.out.println("[1/3] Building client...");
            AtomicReference<ILinkClient> clientRef = new AtomicReference<>();
            ILinkClient client = ILinkClient.builder()
                .onLogin(new OnLoginListener() {
                    @Override
                    public void onLoginSuccess(LoginContext ctx) {
                        System.out.println();
                        System.out.println("[OK] 登录成功!");
                        System.out.println("       Bot ID: " + ctx.getBotId());
                        System.out.println("       User ID: " + ctx.getUserId());
                        System.out.println("       现在可以在微信里给机器人发消息了");
                        System.out.println();
                    }
                    @Override
                    public void onLoginFailure(Throwable th) {
                        System.err.println("[ERROR] 登录失败: " + th.getMessage());
                    }
                })
                .onMessage(messages -> handleMessages(clientRef.get(), messages))
                .build();
            clientRef.set(client);

            // ===== 2. 启动登录 =====
            System.out.println("[2/3] Getting QR code...");
            String qrImgContent = client.executeLogin();
            String qrToken = client.getQrcode();
            System.out.println();

            // ===== 3. 显示二维码 =====
            System.out.println("[3/3] Displaying QR code...");
            displayQrCode(qrImgContent, qrToken);

            // ===== 4. 等待扫码登录 =====
            System.out.println("[INFO] 请用微信扫码，等待登录...");
            System.out.println();
            while (!client.isLoggedIn()) {
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }

            // ===== 5. 登录成功，开始轮询消息 =====
            System.out.println("[INFO] 机器人运行中，按 Ctrl+C 退出");
            System.out.println();

            while (true) {
                try {
                    if (client.isLoggedIn()) {
                        client.getUpdates();
                    }
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    // 静默处理未登录异常，其他错误才打印
                    String msg = e.getMessage();
                    if (msg == null || !msg.toLowerCase().contains("not logged")) {
                        System.err.println("[WARN] " + msg);
                    }
                    Thread.sleep(3000);
                }
            }

        } catch (Exception e) {
            System.err.println("[FATAL] " + e.getMessage());
            e.printStackTrace();
        }
    }

    // ===== 处理收到的消息 =====
    private static synchronized void handleMessages(ILinkClient client, List<WeixinMessage> messages) {
        if (messages == null || messages.isEmpty()) return;

        for (WeixinMessage msg : messages) {
            // 只处理用户发来的消息（非机器人自己发的）
            // message_type = 1 通常是用户消息
            if (msg.getMessage_id() == null) continue;

            // 去重，同一条消息不重复处理
            if (!processedMsgIds.add(msg.getMessage_id())) continue;

            String from = msg.getFrom_user_id();
            String textContent = extractText(msg);

            // 分支 1：消息包含图片 → 看图理解
            if (hasImage(msg)) {
                handleImageMessage(client, msg, from, textContent);
                continue;
            }

            // 其他消息至少需要有文字
            if (textContent == null || textContent.trim().isEmpty()) continue;
            System.out.println("[RECV] <" + from + "> " + textContent);

            // 分支 2：检测"画图"指令 → 文生图
            String imagePrompt = extractImagePrompt(textContent);
            if (imagePrompt != null) {
                handleGenerateImage(client, from, imagePrompt);
                continue;
            }

            // 分支 3：普通文本 → DeepSeek 对话
            handleTextMessage(client, from, textContent);
        }
    }

    // ===== 图片消息处理：看图理解 =====
    private static void handleImageMessage(ILinkClient client, WeixinMessage msg,
                                            String from, String textContent) {
        String question = (textContent == null || textContent.trim().isEmpty())
            ? "请描述这张图片的内容"
            : textContent.replace("[图片]", "").replace("[语音]", "")
                .replace("[文件]", "").replace("[视频]", "").trim();

        System.out.println("[RECV] <" + from + "> [图片] "
            + (question.isEmpty() ? "(无文字问题)" : question));

        // 百炼 Key 未配置 → 提示
        if (!aliyunService.isConfigured()) {
            String hint = "（我暂时无法识别图片，请先配置 DASHSCOPE_API_KEY 后再试）";
            try {
                client.sendTextWithTyping(from, hint, 1500);
                System.out.println("[SEND] " + hint);
            } catch (Exception e) {
                System.err.println("[ERROR] Send failed: " + e.getMessage());
            }
            return;
        }

        // 下载图片字节
        byte[] imageBytes = downloadFirstImage(client, msg);
        if (imageBytes == null || imageBytes.length == 0) {
            try {
                client.sendTextWithTyping(from, "图片下载失败了，请换一张试试～", 1500);
            } catch (Exception e) {
                System.err.println("[ERROR] Send failed: " + e.getMessage());
            }
            return;
        }

        // 调用百炼看图
        try {
            System.out.println("[INFO] 正在调用百炼图片理解模型...");
            String description = aliyunService.understandImage(imageBytes,
                question.isEmpty() ? "请描述这张图片的内容" : question);

            long typingMillis = Math.min(2500, 500 + description.length() * 20L);
            client.sendTextWithTyping(from, description, typingMillis);
            System.out.println("[SEND] " + description.replace("\n", " | "));

            // 图片对话不累积到历史，避免 prompt 膨胀
        } catch (Exception e) {
            System.err.println("[ERROR] 图片理解失败: " + e.getMessage());
            try {
                client.sendTextWithTyping(from, "抱歉，图片识别出问题了："
                    + (e.getMessage() == null ? "未知错误" : e.getMessage()), 1500);
            } catch (Exception ex) {
                System.err.println("[ERROR] Send failed: " + ex.getMessage());
            }
        }
    }

    // ===== 画图指令处理：文生图 =====
    private static void handleGenerateImage(ILinkClient client, String from, String prompt) {
        System.out.println("[RECV] <" + from + "> [画图指令] " + prompt);

        // 百炼 Key 未配置 → 提示
        if (!aliyunService.isConfigured()) {
            String hint = "（我暂时无法生成图片，请先配置 DASHSCOPE_API_KEY 后再试）";
            try {
                client.sendTextWithTyping(from, hint, 1500);
                System.out.println("[SEND] " + hint);
            } catch (Exception e) {
                System.err.println("[ERROR] Send failed: " + e.getMessage());
            }
            return;
        }

        try {
            // 先发一条文字提示告诉用户正在生成
            String pendingMsg = "好的，正在为你画图：" + prompt + "\n（生成图片需要一点时间，请耐心等待～）";
            client.sendTextWithTyping(from, pendingMsg, Math.min(2000, 500 + pendingMsg.length() * 15L));
            System.out.println("[SEND] " + pendingMsg.replace("\n", " | "));

            // 调用百炼生成图片
            System.out.println("[INFO] 正在调用百炼文生图模型...");
            byte[] imageBytes = aliyunService.generateImage(prompt);

            // 发送图片给用户
            String fileName = "ai-generated-" + System.currentTimeMillis() + ".png";
            client.sendImage(from, imageBytes, fileName, null);
            System.out.println("[SEND] [图片] " + fileName + " (" + imageBytes.length + " bytes)");

            // 生成图对话不累积到历史
        } catch (Exception e) {
            System.err.println("[ERROR] 图片生成失败: " + e.getMessage());
            try {
                client.sendTextWithTyping(from, "抱歉，画图出问题了："
                    + (e.getMessage() == null ? "未知错误" : e.getMessage()), 1500);
            } catch (Exception ex) {
                System.err.println("[ERROR] Send failed: " + ex.getMessage());
            }
        }
    }

    // ===== 普通文本消息处理：DeepSeek 对话 =====
    private static void handleTextMessage(ILinkClient client, String from, String userText) {
        String reply = buildReply(userText);
        try {
            long typingMillis = Math.min(2000, 300 + reply.length() * 20L);
            client.sendTextWithTyping(from, reply, typingMillis);
            System.out.println("[SEND] " + reply.replace("\n", " | "));
            appendToHistory(userText, reply);
        } catch (Exception e) {
            System.err.println("[ERROR] Send failed: " + e.getMessage());
        }
    }

    // ===== 辅助：消息是否包含图片 =====
    private static boolean hasImage(WeixinMessage msg) {
        if (msg.getItem_list() == null) return false;
        for (MessageItem item : msg.getItem_list()) {
            if (item.getImage_item() != null) return true;
        }
        return false;
    }

    // ===== 辅助：下载消息中第一张图片为字节 =====
    private static byte[] downloadFirstImage(ILinkClient client, WeixinMessage msg) {
        if (msg.getItem_list() == null) return null;
        for (MessageItem item : msg.getItem_list()) {
            if (item.getImage_item() != null) {
                try {
                    return client.downloadImageFromMessageItem(item);
                } catch (Exception e) {
                    System.err.println("[ERROR] 图片下载失败: " + e.getMessage());
                    return null;
                }
            }
        }
        return null;
    }

    // ===== 辅助：检测"画图"指令，返回 prompt（不是画图指令返回 null） =====
    private static String extractImagePrompt(String userText) {
        if (userText == null) return null;
        String t = userText.trim();

        // 注意：长的前缀要放在前面，避免被短的前缀截断
        // 例如必须先检查"给我画一张"，再检查"给我画"
        String[] prefixes = {
            // 最明确的：画图/生成图
            "画图：", "画图:", "画图 ", "画图",
            "生成图：", "生成图:", "生成图 ", "生成图",

            // 一张类：画一张/生成一张/来一张/给一张
            "画一张：", "画一张:", "画一张 ", "画一张",
            "生成一张：", "生成一张:", "生成一张 ", "生成一张",
            "来一张：", "来一张:", "来一张 ", "来一张",
            "给一张：", "给一张:", "给一张 ", "给一张",
            "来张：", "来张:", "来张 ", "来张",

            // 请求类：帮我画/给我画/帮我生成/给我生成
            "帮我画一张", "帮我画一张 ", "帮我画一张：", "帮我画一张:",
            "给我画一张", "给我画一张 ", "给我画一张：", "给我画一张:",
            "帮我画个", "帮我画个 ", "帮我画个：", "帮我画个:",
            "给我画个", "给我画个 ", "给我画个：", "给我画个:",
            "帮我画", "帮我画 ", "帮我画：", "帮我画:",
            "给我画", "给我画 ", "给我画：", "给我画:",
            "帮我生成一张", "帮我生成一张 ", "帮我生成",
            "给我生成一张", "给我生成一张 ", "给我生成",

            // 简短类：画个/画/生成
            "画个", "画个 ", "画个：", "画个:",
            "画 ", "画"
        };

        for (String prefix : prefixes) {
            if (t.startsWith(prefix)) {
                String prompt = t.substring(prefix.length()).trim();
                // 去掉开头的"..." 中的冒号和空格
                if (prompt.startsWith("：") || prompt.startsWith(":")) {
                    prompt = prompt.substring(1).trim();
                }
                // 去掉"一张图片""图片"等冗余词（保留核心描述）
                String[] redundant = {"一张图片", "一张图", "张图片", "张图", "图片"};
                for (String r : redundant) {
                    if (prompt.equals(r)) {
                        // 只说了"画图一张图片"这类，没有实际描述，当无效处理
                        return null;
                    }
                }
                return prompt.isEmpty() ? null : prompt;
            }
        }
        return null;
    }

    // ===== 从消息中提取文字 =====
    private static String extractText(WeixinMessage msg) {
        if (msg.getItem_list() == null) return null;
        StringBuilder sb = new StringBuilder();
        for (MessageItem item : msg.getItem_list()) {
            if (item.getType() == 1 && item.getText_item() != null) {
                sb.append(item.getText_item().getText());
            } else if (item.getImage_item() != null) {
                sb.append("[图片]");
            } else if (item.getVoice_item() != null) {
                sb.append("[语音]");
            } else if (item.getFile_item() != null) {
                sb.append("[文件]");
            } else if (item.getVideo_item() != null) {
                sb.append("[视频]");
            }
        }
        return sb.toString();
    }

    // ===== 生成回复：优先 DeepSeek，没 key 时 echo =====
    private static String buildReply(String userText) {
        if (userText == null || userText.trim().isEmpty()) {
            return "你好！有什么可以帮你的吗？";
        }
        String t = userText.trim();

        // 特殊命令
        if (t.equalsIgnoreCase("help") || t.equals("帮助") || t.equals("?")) {
            return "我可以做的事情："
                + "\n1. 文本对话（接入 DeepSeek 大模型）"
                + "\n2. 看图识别（发送图片即可，同时接入阿里云百炼视觉模型）"
                + "\n3. 文生图（说「画图 一只在月球上的猫」即可生成图片）"
                + "\n（发送 'clear' 可清空对话记忆）";
        }
        if (t.equalsIgnoreCase("clear") || t.equals("清空") || t.equals("重置")) {
            conversationHistory.setLength(0);
            return "对话记忆已清空，我们重新开始聊天吧！";
        }

        // 没配置 API Key：echo 模式
        if (DEEPSEEK_API_KEY == null || DEEPSEEK_API_KEY.trim().isEmpty()) {
            return "（Echo模式）你说: " + userText
                + "\n提示：配置环境变量 DEEPSEEK_API_KEY 开启智能对话";
        }

        // DeepSeek
        try {
            return callDeepSeek(userText);
        } catch (Exception e) {
            System.err.println("[ERROR] DeepSeek failed: " + e.getMessage());
            return "抱歉，大脑暂时短路了：" + e.getMessage();
        }
    }

    // ===== DeepSeek API 调用 =====
    private static String callDeepSeek(String userText) throws Exception {
        HttpClient http = HttpClient.newBuilder()
            .connectTimeout(java.time.Duration.ofSeconds(15))
            .build();

        // 构造请求体 JSON
        String messages =
            "[{\"role\":\"system\",\"content\":" + jsonEscape(SYSTEM_PROMPT) + "}"
            + (conversationHistory.length() > 0 ? "," + conversationHistory.toString() : "")
            + ",{\"role\":\"user\",\"content\":" + jsonEscape(userText) + "}]";

        String body = "{"
            + "\"model\":\"" + DEEPSEEK_MODEL + "\","
            + "\"messages\":" + messages + ","
            + "\"temperature\":0.8,"
            + "\"max_tokens\":1024"
            + "}";

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(DEEPSEEK_URL))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer " + DEEPSEEK_API_KEY)
            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
            .build();

        HttpResponse<String> response = http.send(request,
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

        if (response.statusCode() != 200) {
            throw new Exception("HTTP " + response.statusCode()
                + ": " + (response.body().length() > 200 ? response.body().substring(0, 200) + "..." : response.body()));
        }

        // 从 JSON 中提取 content 字段
        String content = extractContent(response.body());
        if (content == null || content.trim().isEmpty()) {
            throw new Exception("Could not parse response JSON");
        }
        return content.trim();
    }

    // ===== 从 JSON 中提取 content 字段 =====
    private static String extractContent(String json) {
        int idx = json.lastIndexOf("\"content\"");
        if (idx < 0) return null;
        int colon = json.indexOf(":", idx);
        if (colon < 0) return null;
        int q1 = json.indexOf("\"", colon + 1);
        if (q1 < 0) return null;
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
                    case '/': sb.append('/'); break;
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
        return sb.toString();
    }

    // ===== JSON 字符串转义 =====
    private static String jsonEscape(String s) {
        if (s == null) return "\"\"";
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
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

    // ===== 把消息加入对话历史 =====
    private static void appendToHistory(String userText, String assistantReply) {
        if (conversationHistory.length() > 6000) conversationHistory.setLength(0);
        if (conversationHistory.length() > 0) conversationHistory.append(",");
        conversationHistory.append("{\"role\":\"user\",\"content\":").append(jsonEscape(userText)).append("},");
        conversationHistory.append("{\"role\":\"assistant\",\"content\":").append(jsonEscape(assistantReply)).append("}");
    }

    // ===== 显示二维码 =====
    private static void displayQrCode(String qrImgContent, String qrToken) {
        if (qrImgContent == null || qrImgContent.trim().isEmpty()) {
            System.out.println("[WARN] 没有获取到二维码");
            return;
        }

        String content = qrImgContent.trim();
        System.out.println();
        System.out.println("       扫码链接: " + content);
        System.out.println();

        // URL 直接浏览器打开
        if (content.toLowerCase().startsWith("http://") || content.toLowerCase().startsWith("https://")) {
            if (Desktop.isDesktopSupported()) {
                try {
                    Desktop.getDesktop().browse(new URI(content));
                    System.out.println("       \uD83C\uDF10 已在浏览器打开，请用微信扫码或在页面中确认登录");
                } catch (Exception e) {
                    System.out.println("       打开失败，请手动访问上面的链接");
                }
            } else {
                System.out.println("       请手动在浏览器中打开上面的链接");
            }
            return;
        }

        // 其他情况（base64/data URL）—— 只打开一个本地HTML页面显示二维码图片
        try {
            byte[] imageBytes = decodeImageBytes(content);
            if (imageBytes == null || imageBytes.length == 0) {
                System.out.println("[WARN] 二维码数据无法解析");
                return;
            }
            String base64Img = "data:image/png;base64," + Base64.getEncoder().encodeToString(imageBytes);
            File htmlFile = new File("qrcode.html");
            String html = "<!DOCTYPE html><html><head><meta charset=\"UTF-8\"><title>微信扫码登录</title>"
                + "<style>body{text-align:center;padding:40px;font-family:sans-serif;background:#f5f5f5;}"
                + "h2{color:#07c160;}img{width:300px;height:300px;border:1px solid #ddd;padding:10px;background:white;}"
                + "</style></head><body><h2>微信扫码登录</h2>"
                + "<img src=\"" + base64Img + "\"/>"
                + "<p>用微信扫一扫确认登录</p></body></html>";
            java.nio.file.Files.write(htmlFile.toPath(), html.getBytes(StandardCharsets.UTF_8));
            Desktop.getDesktop().open(htmlFile);
            System.out.println("       \uD83C\uDF10 已在浏览器打开扫码页面");
        } catch (Exception e) {
            System.err.println("[ERROR] 二维码显示失败: " + e.getMessage());
        }
        System.out.println();
    }

    // ===== 解析二维码图片字节 =====
    // 支持多种格式：URL、data URL、纯base64
    private static byte[] decodeImageBytes(String content) {
        if (content == null) return null;
        String c = content.trim();
        if (c.isEmpty()) return null;

        if (c.toLowerCase().startsWith("data:image")) {
            int comma = c.indexOf(',');
            if (comma > 0) c = c.substring(comma + 1).trim();
            c = c.replaceAll("\\s+", "");
            try {
                return Base64.getDecoder().decode(c);
            } catch (IllegalArgumentException e) {
                return null;
            }
        }

        if (c.toLowerCase().startsWith("http://") || c.toLowerCase().startsWith("https://")) {
            try {
                HttpClient http = HttpClient.newBuilder()
                    .connectTimeout(java.time.Duration.ofSeconds(15))
                    .followRedirects(HttpClient.Redirect.ALWAYS)
                    .build();
                HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(c))
                    .GET().build();
                HttpResponse<byte[]> resp = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
                if (resp.statusCode() == 200) return resp.body();
            } catch (Exception e) {
                return null;
            }
            return null;
        }

        try {
            String clean = c.replaceAll("\\s+", "");
            return Base64.getDecoder().decode(clean);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}