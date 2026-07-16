package com.xwc.demo;

import com.github.wechat.ilink.sdk.ILinkClient;
import com.github.wechat.ilink.sdk.core.config.ILinkConfig;
import com.github.wechat.ilink.sdk.core.listener.OnMessageListener;
import com.github.wechat.ilink.sdk.core.model.MessageItem;
import com.github.wechat.ilink.sdk.core.model.WeixinMessage;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class WeChatBotService {

    private static final Logger log = LoggerFactory.getLogger(WeChatBotService.class);

    private ILinkClient client;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final Queue<BotMessage> messageLog = new ConcurrentLinkedQueue<>();
    private final Set<String> activeUsers = Collections.synchronizedSet(new HashSet<>());
    private Thread pollerThread;

    @PostConstruct
    public void init() {
        log.info("WeChatBotService 初始化完成，等待调用 start() 启动登录");
    }

    @PreDestroy
    public void destroy() {
        stop();
    }

    public synchronized BotStartResult start() throws Exception {
        if (client != null && client.isLoggedIn()) {
            return new BotStartResult(false, "机器人已登录", null);
        }

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

                            activeUsers.add(from);

                            String text = extractText(msg);
                            boolean hasImage = hasImage(msg);
                            boolean hasVoice = hasVoice(msg);
                            boolean hasVideo = hasVideo(msg);
                            boolean hasFile = hasFile(msg);

                            String type = "unknown";
                            String content = text;
                            if (hasImage) { type = "image"; content = "[图片]"; }
                            else if (hasVoice) { type = "voice"; content = "[语音]"; }
                            else if (hasVideo) { type = "video"; content = "[视频]"; }
                            else if (hasFile) { type = "file"; content = "[文件]"; }
                            else if (text != null) { type = "text"; }

                            BotMessage botMsg = new BotMessage(System.currentTimeMillis(), from, type, content, "received");
                            messageLog.offer(botMsg);
                            if (messageLog.size() > 200) messageLog.poll();

                            if (text != null) {
                                String reply = generateReply(text);
                                try {
                                    sendMessage(from, reply);
                                } catch (IOException e) {
                                    log.warn("自动回复失败: {}", e.getMessage());
                                }
                            }
                        }
                    }
                })
                .build();

        log.info("正在获取登录二维码...");
        String qrContent = client.executeLogin();
        String qrHtmlPath = saveQrCodePage(qrContent);

        running.set(true);
        pollerThread = new Thread(() -> {
            log.info("消息监听线程已启动");
            while (running.get()) {
                try {
                    if (client.isLoggedIn()) {
                        client.getUpdates();
                    }
                    Thread.sleep(2000);
                } catch (Exception e) {
                    try { Thread.sleep(3000); } catch (InterruptedException ie) { break; }
                }
            }
            log.info("消息监听线程已退出");
        });
        pollerThread.setDaemon(true);
        pollerThread.start();

        return new BotStartResult(true, "请用微信扫码登录", qrHtmlPath);
    }

    public synchronized void stop() {
        running.set(false);
        if (pollerThread != null) {
            pollerThread.interrupt();
        }
        if (client != null) {
            try {
                client.close();
            } catch (Exception ignored) {}
            client = null;
        }
        log.info("微信机器人已停止");
    }

    public boolean isLoggedIn() {
        return client != null && client.isLoggedIn();
    }

    public BotStatus getStatus() {
        return new BotStatus(
                client != null,
                isLoggedIn(),
                activeUsers.size(),
                messageLog.size(),
                client != null ? client.getConnectionStatus().name() : "NOT_INITIALIZED"
        );
    }

    public void sendMessage(String toUserId, String text) throws IOException {
        if (client == null || !client.isLoggedIn()) {
            throw new IllegalStateException("机器人未登录，请先调用 /bot/start 扫码登录");
        }
        client.sendText(toUserId, text);
        BotMessage botMsg = new BotMessage(System.currentTimeMillis(), toUserId, "text", text, "sent");
        messageLog.offer(botMsg);
        if (messageLog.size() > 200) messageLog.poll();
    }

    public List<BotMessage> getMessages() {
        List<BotMessage> list = new ArrayList<>(messageLog);
        list.sort((a, b) -> Long.compare(b.timestamp, a.timestamp));
        return list;
    }

    public Set<String> getActiveUsers() {
        return new HashSet<>(activeUsers);
    }

    // ========== 辅助方法 ==========

    private String saveQrCodePage(String qrUrlOrContent) {
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
                html = "<!DOCTYPE html><html><head><meta charset=\"UTF-8\"><title>登录</title></head>" +
                        "<body style=\"text-align:center;padding:40px;background:#f5f5f5;font-family:Arial;\">" +
                        "<div style=\"max-width:500px;margin:50px auto;background:white;padding:40px;border-radius:10px;\">" +
                        "<h1 style=\"color:#07c160;\">微信扫码登录</h1>" +
                        "<div style=\"margin:30px 0;\"><img src=\"" + (imgSrc == null ? "" : imgSrc) + "\" style=\"width:300px;height:300px;border:4px solid #07c160;border-radius:10px;padding:10px;\" /></div>" +
                        "<p style=\"color:#666;font-size:16px;\">请使用微信扫描上方二维码</p>" +
                        "</div></body></html>";
            }
            String path = "qrcode.html";
            Files.write(Paths.get(path), html.getBytes(StandardCharsets.UTF_8));
            return path;
        } catch (IOException e) {
            log.warn("保存二维码失败: {}", e.getMessage());
            return null;
        }
    }

    private String extractText(WeixinMessage msg) {
        if (msg.getItem_list() == null) return null;
        for (MessageItem item : msg.getItem_list()) {
            if (item.getText_item() != null) {
                return item.getText_item().getText();
            }
        }
        return null;
    }

    private boolean hasImage(WeixinMessage msg) {
        if (msg.getItem_list() == null) return false;
        for (MessageItem item : msg.getItem_list()) {
            if (item.getImage_item() != null) return true;
        }
        return false;
    }

    private boolean hasVoice(WeixinMessage msg) {
        if (msg.getItem_list() == null) return false;
        for (MessageItem item : msg.getItem_list()) {
            if (item.getVoice_item() != null) return true;
        }
        return false;
    }

    private boolean hasVideo(WeixinMessage msg) {
        if (msg.getItem_list() == null) return false;
        for (MessageItem item : msg.getItem_list()) {
            if (item.getVideo_item() != null) return true;
        }
        return false;
    }

    private boolean hasFile(WeixinMessage msg) {
        if (msg.getItem_list() == null) return false;
        for (MessageItem item : msg.getItem_list()) {
            if (item.getFile_item() != null) return true;
        }
        return false;
    }

    // ========== 智能回复（简化版） ==========

    private final Random rand = new Random();

    private String generateReply(String text) {
        if (text == null) return "你好呀~";
        String t = text.toLowerCase().trim();

        if (matchesAny(t, "你好", "您好", "hi", "hello", "嗨"))
            return pickRandom("你好呀！有什么可以帮你的吗？", "Hi~ 今天过得怎么样？");

        if (matchesAny(t, "在吗", "在不", "有人吗"))
            return pickRandom("在的！随时为你服务~", "我一直都在哦，有什么事吗？");

        if (matchesAny(t, "你叫什么", "你是谁", "名字", "怎么称呼"))
            return "我是微信 iLink 机器人，你可以叫我小助手~";

        if (matchesAny(t, "谢谢", "感谢", "多谢"))
            return pickRandom("不客气~", "别客气，这是我应该做的~");

        if (matchesAny(t, "再见", "拜拜", "bye"))
            return pickRandom("好的，再见啦！", "拜拜！有时间再聊哦~");

        if (matchesAny(t, "几点", "时间", "现在几点", "几号", "日期"))
            return "现在是 " + new java.text.SimpleDateFormat("yyyy年MM月dd日 HH:mm:ss").format(new Date());

        if (matchesAny(t, "厉害", "棒", "不错", "666"))
            return pickRandom("谢谢夸奖~", "嘿嘿，被夸到了~");

        if (matchesAny(t, "讲个笑话", "说个笑话", "来个笑话", "讲笑话", "笑话"))
            return tellJoke();

        if (matchesAny(t, "在干嘛", "在做什么", "干嘛呢"))
            return pickRandom("我在陪你聊天呀~", "正在思考人生，然后你就来了！");

        if (matchesAny(t, "想你了", "想你", "好想你"))
            return pickRandom("我也想你呀~", "那我们多聊会儿！");

        if (t.length() <= 2)
            return pickRandom("嗯嗯？", "怎么了？", "你说~");

        return pickRandom("嗯嗯，我在听你说~", "然后呢？继续说~", "有意思！能多聊聊吗？",
                "原来是这样啊~", "好的好的！我记住了~", "哈哈，真有趣！");
    }

    private boolean matchesAny(String text, String... keywords) {
        for (String kw : keywords) {
            if (text.contains(kw.toLowerCase())) return true;
        }
        return false;
    }

    private String pickRandom(String... options) {
        return options[rand.nextInt(options.length)];
    }

    private String tellJoke() {
        String[] jokes = {
                "为什么程序员喜欢黑色？因为彩色会让他们分心去调试颜色！",
                "我的钱包就像洋葱一样，每次打开我都想哭...",
                "企鹅的肚子为什么是白的？因为它手短，洗澡只能洗到肚子！",
                "程序员最讨厌的事：1. 写注释 2. 别人不写注释 3. 帮别人改代码"
        };
        return jokes[rand.nextInt(jokes.length)];
    }

    // ========== 内部数据类 ==========

    public record BotMessage(long timestamp, String userId, String type, String content, String direction) {}
    public record BotStatus(boolean initialized, boolean loggedIn, int activeUsers, int messageCount, String connectionStatus) {}
    public record BotStartResult(boolean success, String message, String qrHtmlPath) {}
}