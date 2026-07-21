package com.xwc.demo.wechat.iLink.demo;

import com.xwc.demo.wechat.iLink.ILinkClient;
import com.xwc.demo.wechat.iLink.core.config.ILinkConfig;
import com.xwc.demo.wechat.iLink.core.listener.OnMessageListener;
import com.xwc.demo.wechat.iLink.core.model.FileItem;
import com.xwc.demo.wechat.iLink.core.model.MessageItem;
import com.xwc.demo.wechat.iLink.core.model.VoiceItem;
import com.xwc.demo.wechat.iLink.core.model.WeixinMessage;
import com.xwc.demo.llm.VoiceGeneration;
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
    private static final Map<String, String> userVoicePref = new ConcurrentHashMap<>();  // 用户音色偏好
    private static final int MEMORY_SIZE = 10;

    // ========== 音色系统 ==========

    private static final String VOICE_FEMALE = "Cherry";
    private static final String VOICE_MALE   = "Ethan";
    private static final String VOICE_CHILD  = "Pip";
    private static final String VOICE_ELDER  = "Eldric Sage";

    /** 完整音色目录：按分类列出所有可用音色 */
    private static final String[][] VOICE_CATALOG = {
        // {编号, 代码, 中文名, 描述, 分类}
        // === 女声 ===
        {"1",  "Cherry",     "芊悦",   "阳光亲切小姐姐",         "女声"},
        {"2",  "Serena",     "苏瑶",   "温柔小姐姐",             "女声"},
        {"3",  "Chelsie",    "千雪",   "二次元虚拟女友",         "女声"},
        {"4",  "Momo",       "茉兔",   "撒娇搞怪",              "女声"},
        {"5",  "Vivian",     "十三",   "拽拽的小暴躁",           "女声"},
        {"6",  "Maia",       "四月",   "知性温柔",              "女声"},
        {"7",  "Bella",      "萌宝",   "可爱小萝莉",             "女声"},
        {"8",  "Bunny",      "萌小姬", "\"萌属性\"爆棚的小萝莉",   "女声"},
        {"9",  "Jennifer",   "詹妮弗", "品牌级美语女声",         "女声"},
        {"10", "Katerina",   "卡捷琳", "御姐音色",              "女声"},
        {"11", "Mia",        "乖小妹", "温顺乖巧",              "女声"},
        {"12", "Bellona",    "燕铮莺", "声音洪亮",              "女声"},
        {"13", "Elias",      "墨讲师", "学术严谨",              "女声"},
        {"14", "Nini",       "邻家妹", "糯米糍一样软黏",         "女声"},
        {"15", "Ebona",      "诡婆婆", "诡异低语",              "女声"},
        {"16", "Stella",     "少女月", "甜到发腻的少女音",       "女声"},
        {"17", "Sonrisa",    "索尼莎", "热情拉美大姐",           "女声"},
        {"18", "Sohee",      "素熙",   "温柔韩国欧尼",           "女声"},
        {"19", "Ono Anna",   "小野杏", "鬼灵精怪青梅竹马",       "女声"},
        // === 男声 ===
        {"20", "Ethan",      "晨煦",   "阳光温暖男声",           "男声"},
        {"21", "Moon",       "月白",   "率性帅气",              "男声"},
        {"22", "Kai",        "凯",     "耳朵SPA",               "男声"},
        {"23", "Nofish",     "不吃鱼", "不会翘舌音的设计师",     "男声"},
        {"24", "Ryan",       "甜茶",   "节奏拉满戏感炸裂",       "男声"},
        {"25", "Aiden",      "艾登",   "精通厨艺美语男孩",       "男声"},
        {"26", "Mochi",      "沙小弥", "聪明伶俐小大人",         "男声"},
        {"27", "Vincent",    "田叔",   "独特沙哑烟嗓",           "男声"},
        {"28", "Neil",       "阿闻",   "最专业新闻主持人",       "男声"},
        {"29", "Bodega",     "博德加", "热情西班牙大叔",         "男声"},
        {"30", "Alek",       "阿列克", "战斗民族的冷",           "男声"},
        {"31", "Dolce",      "多尔切", "慵懒意大利大叔",         "男声"},
        {"32", "Lenn",       "莱恩",   "理性底色叛逆细节",       "男声"},
        {"33", "Emilien",    "埃米尔", "浪漫法国大哥哥",         "男声"},
        {"34", "Andre",      "安德雷", "声音磁性的沉稳男生",     "男声"},
        {"35", "Radio Gol",  "戈尔",   "足球诗人",              "男声"},
        // === 小孩 ===
        {"36", "Pip",        "顽屁",   "调皮捣蛋小男孩",         "小孩"},
        {"37", "Seren",      "小婉",   "温和舒缓的声线",         "小孩"},
        // === 老人 ===
        {"38", "Eldric Sage","沧明子", "沉稳睿智老者",           "老人"},
        {"39", "Arthur",     "徐大爷", "岁月旱烟浸泡的质朴嗓音", "老人"},
        // === 方言 ===
        {"40", "Jada",       "阿珍",   "上海话",                "方言"},
        {"41", "Dylan",      "晓东",   "北京话",                "方言"},
        {"42", "Li",         "老李",   "南京话",                "方言"},
        {"43", "Marcus",     "秦川",   "陕西话",                "方言"},
        {"44", "Roy",        "阿杰",   "闽南语",                "方言"},
        {"45", "Peter",      "李彼得", "天津话",                "方言"},
        {"46", "Sunny",      "晴儿",   "四川话(女)",            "方言"},
        {"47", "Eric",       "程川",   "四川话(男)",            "方言"},
        {"48", "Rocky",      "阿强",   "粤语(男)",              "方言"},
        {"49", "Kiki",       "阿清",   "粤语(女)",              "方言"},
    };

    /** 编号 → voice 代码 */
    private static final Map<String, String> VOICE_BY_NUM = new HashMap<>();
    /** 中文名/别名 → voice 代码 */
    private static final Map<String, String> VOICE_BY_NAME = new HashMap<>();
    /** 分类 → 默认 voice（男声/女声/小孩/老人） */
    private static final Map<String, String> VOICE_BY_CATEGORY = new LinkedHashMap<>();
    static {
        for (String[] v : VOICE_CATALOG) {
            VOICE_BY_NUM.put(v[0], v[1]);
            VOICE_BY_NAME.put(v[2], v[1]);
            VOICE_BY_NAME.put(v[1].toLowerCase(), v[1]); // 代码直通
        }
        VOICE_BY_CATEGORY.put("男声", VOICE_MALE);
        VOICE_BY_CATEGORY.put("女声", VOICE_FEMALE);
        VOICE_BY_CATEGORY.put("小孩", VOICE_CHILD);
        VOICE_BY_CATEGORY.put("老人", VOICE_ELDER);
        // 别名 → 分类默认
        for (String k : new String[]{"男声","男音","男生","男的","男","男性","大叔","叔叔"})
            VOICE_BY_NAME.put(k, VOICE_MALE);
        for (String k : new String[]{"女声","女音","女生","女的","女","女性","姐姐","妹子"})
            VOICE_BY_NAME.put(k, VOICE_FEMALE);
        for (String k : new String[]{"小孩","儿童","孩子","小朋友","正太","萝莉","童声"})
            VOICE_BY_NAME.put(k, VOICE_CHILD);
        for (String k : new String[]{"老人","老者","长者","爷爷","奶奶","大爷","婆婆","长辈"})
            VOICE_BY_NAME.put(k, VOICE_ELDER);
    }

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

    // ==================== 语音生成配置（TTS）====================
    private static String ttsBaseUrl = "";
    private static String ttsApiKey = "";
    private static String ttsModel = "";
    private static String ttsVoice = "";

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
        loadMemory();

        // Ctrl+C / kill 时自动保存
        Runtime.getRuntime().addShutdownHook(new Thread(WeChatBot::saveMemory));

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
                            String text = extractText(msg);  // ← 拿 text_item.text
                            boolean hasImage = hasImage(msg);
                            boolean hasVoice = hasVoice(msg);   // ← 检查 item_list 里有没有 voice_item
                            boolean hasVideo = hasVideo(msg);
                            boolean hasFile = hasFile(msg);

                            System.out.println("\n[收到] " + from + " | msg_type=" + msg.getMessage_type());
                            // dump 所有 item 类型（诊断 PDF 为什么进了 text 通道）
                            if (msg.getItem_list() != null) {
                                for (int i = 0; i < msg.getItem_list().size(); i++) {
                                    MessageItem mi = msg.getItem_list().get(i);
                                    String t = mi.getText_item() != null ? "text" :
                                               mi.getImage_item() != null ? "image" :
                                               mi.getVoice_item() != null ? "voice" :
                                               mi.getFile_item() != null ? "file" :
                                               mi.getVideo_item() != null ? "video" : "?";
                                    System.out.println("  item[" + i + "] type=" + mi.getType() + " " + t);
                                }
                            }
                            if (text != null) {
                                String preview = text.length() > 100 ? text.substring(0, 100) + "..." : text;
                                System.out.println("  文本: " + preview);
                            }
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

        saveMemory();
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

        // 2. 处理语音消息：先取微信自带的语音转文字，识别成功后走 LLM 对话
        if (hasVoice) {
            handleVoiceMessage(from, msg, text);
            return;
        }

        if (hasVideo) { client.sendText(from, "（我看到视频，但目前只能理解文字~ 打字告诉我你想聊什么吧）"); return; }
        if (hasFile) { handleFileMessage(from, msg); return; }

        // 2.5 文件兜底检测：微信可能把文件放在非 file_item 里
        if (tryHandleAsFile(from, msg)) return;

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

        // 特殊指令：切换音色
        String voiceSwitch = detectVoiceCommand(t);
        if (voiceSwitch != null) {
            if ("__LIST__".equals(voiceSwitch)) {
                sendVoiceCatalog(from);
                return;
            }
            userVoicePref.put(from, voiceSwitch);
            client.sendText(from, "好的，已切换为" + voiceDisplayName(voiceSwitch) + "～");
            return;
        }

        // 4. 检测嵌入在文本里的文件（微信可能把 PDF 内容当文本发过来）
        if (isEmbeddedFile(t)) {
            tryProcessEmbeddedFile(from, t);
            return;
        }

        // 5. 检测是否是图片生成请求
        if (isImageGenerationRequest(t)) {
            handleImageGeneration(from, t);
            return;
        }

        // 6. 普通文本对话
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
            byte[] imageData = downloadUserImage(msg);
            if (imageData == null || imageData.length == 0) {
                return "（图片下载失败了，请再发一次试试）";
            }

            System.out.println("  [图片理解] 下载成功，大小: " + imageData.length / 1024 + " KB");

            String base64Image = Base64.getEncoder().encodeToString(imageData);
            String mimeType = detectMimeType(imageData);
            String userQuestion = (text != null && !text.trim().isEmpty())
                                  ? text.trim()
                                  : "这张图片里有什么？请描述一下内容";

            // 调用视觉模型（含历史上下文）
            String analysis = callLlmWithImage(from, base64Image, mimeType, userQuestion);

            // ⭐ 存入对话记忆，后续文字消息能联系上下文
            List<LlmMsg> history = userMemoryObj.computeIfAbsent(from, k -> new ArrayList<>());
            // 用自然语言记，模型不会刻意提"之前那张图"
            String q = (text != null && !text.trim().isEmpty()) ? text.trim() : "帮我看看这个";
            history.add(new LlmMsg("user", q));
            history.add(new LlmMsg("assistant", analysis));
            while (history.size() > MEMORY_SIZE * 2) history.remove(0);

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
        systemMsg.put("content", llmSystemPrompt);
        messages.add(systemMsg);

        // ⭐ 注入历史对话（让模型能联系上下文理解图片）
        List<LlmMsg> history = userMemoryObj.get(userId);
        if (history != null) {
            for (LlmMsg m : history) {
                Map<String, Object> hMsg = new LinkedHashMap<>();
                hMsg.put("role", m.role);
                hMsg.put("content", m.content);
                messages.add(hMsg);
            }
        }

        // 用户消息（包含图片 + 文字问题）
        Map<String, Object> userMsg = new LinkedHashMap<>();
        List<Map<String, Object>> contentList = new ArrayList<>();

        Map<String, Object> textPart = new LinkedHashMap<>();
        textPart.put("type", "text");
        textPart.put("text", question);
        contentList.add(textPart);

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

    // ==================== 文件识别 ====================

    /** 兜底：遍历所有 item 类型，找到可下载的媒体当作文件处理 */
    private static boolean tryHandleAsFile(String from, WeixinMessage msg) {
        if (msg.getItem_list() == null) return false;
        for (MessageItem item : msg.getItem_list()) {
            try {
                byte[] data = client.downloadMediaFromMessageItem(item);
                if (data != null && data.length > 100) {
                    // 尝试提取文字
                    String text;
                    try {
                        text = com.xwc.demo.llm.FileReader.extract(data, "file");
                    } catch (Exception e) {
                        // 不是已知格式，当作二进制跳过
                        continue;
                    }
                    if (text.length() >= 10) {
                        System.out.println("  [文件识别] 兜底检测到文件，提取 " + text.length() + " 字");
                        String prompt = text.length() > 3000
                                ? "总结这份文件的要点：\n" + text.substring(0, 3000)
                                : "总结这份文件的要点：\n" + text;
                        String reply = callLlm(from, prompt);
                        List<LlmMsg> history = userMemoryObj.computeIfAbsent(from, k -> new ArrayList<>());
                        history.add(new LlmMsg("user", "帮我看看这份文件"));
                        history.add(new LlmMsg("assistant", reply));
                        while (history.size() > MEMORY_SIZE * 2) history.remove(0);
                        client.sendText(from, reply);
                        return true;
                    }
                }
            } catch (Exception ignored) {}
        }
        return false;
    }

    private static void handleFileMessage(String from, WeixinMessage msg) throws Exception {
        MessageItem fileMsg = null;
        if (msg.getItem_list() != null) {
            for (MessageItem item : msg.getItem_list()) {
                if (item.getFile_item() != null) { fileMsg = item; break; }
            }
        }
        if (fileMsg == null || fileMsg.getFile_item().getMedia() == null) {
            client.sendText(from, "（没拿到文件内容，请再发一次）");
            return;
        }

        FileItem fi = fileMsg.getFile_item();
        String fileName = fi.getFile_name() != null ? fi.getFile_name() : "unknown";
        System.out.println("  [文件识别] 收到文件: " + fileName);

        // 检查格式
        String name = fileName.toLowerCase();
        if (!name.endsWith(".pdf") && !name.endsWith(".docx") && !name.endsWith(".txt")
                && !name.endsWith(".doc") && !name.endsWith(".md")) {
            client.sendText(from, "（暂不支持 " + fileName + " 格式，目前支持 PDF、Word、TXT）");
            return;
        }

        try {
            byte[] data = client.downloadFileFromMessageItem(fileMsg);
            System.out.println("  [文件识别] 下载成功: " + data.length + " 字节");
            String text = com.xwc.demo.llm.FileReader.extract(data, fileName);
            System.out.println("  [文件识别] 提取 " + text.length() + " 字");

            if (text.length() < 10) {
                client.sendText(from, "（文件内容无法识别，可能是扫描版PDF或加密文件）");
                return;
            }

            String prompt = text.length() > 3000
                    ? "总结这份文件（" + fileName + "）的要点：\n" + text.substring(0, 3000)
                    : "总结这份文件（" + fileName + "）的要点：\n" + text;
            String reply = callLlm(from, prompt);

            // 存入记忆
            List<LlmMsg> history = userMemoryObj.computeIfAbsent(from, k -> new ArrayList<>());
            history.add(new LlmMsg("user", "帮我看看这份文件（" + fileName + "）"));
            history.add(new LlmMsg("assistant", reply));
            while (history.size() > MEMORY_SIZE * 2) history.remove(0);

            client.sendText(from, reply);

        } catch (Exception e) {
            System.err.println("  [文件识别] 失败: " + e.getMessage());
            e.printStackTrace();
            client.sendText(from, "（文件解析失败: " + e.getMessage() + "）");
        }
    }

    // ==================== 语音识别 & 回复 ====================

    /**
     * 处理微信语音消息：
     *   1. 优先拿 voice_item.getText()——微信服务端已做了语音转文字（普通话识别率很高）
     *   2. 如果没转出来，下载语音文件自己尝试识别（目前只做提示）
     *   3. 拿到文字后 -> 和普通文本消息走一样的流程（可能是图片生成请求 / 普通对话）
     */
    private static void handleVoiceMessage(String from, WeixinMessage msg, String originalText) throws Exception {
        System.out.println("  [语音识别] 开始处理语音消息...");

        // 第1步：从消息里捞出 VoiceItem
        VoiceItem voiceItem = findVoiceItem(msg);
        if (voiceItem == null) {
            client.sendText(from, "（没拿到语音内容，请再发一次试试）");
            return;
        }

        // ====== 方式 A：微信服务端已填充的 text 字段（首选，零成本）======
        String voiceText = voiceItem.getText();
        if (voiceText != null && !voiceText.trim().isEmpty()) {
            voiceText = voiceText.trim();
            System.out.println("  [语音识别] ✅ 微信语音转文字: \"" + voiceText + "\"");

            // 先检测是不是音色切换或画图请求
            String voiceSwitch = detectVoiceCommand(voiceText);
            if (voiceSwitch != null) {
                if ("__LIST__".equals(voiceSwitch)) {
                    sendVoiceCatalog(from);
                    return;
                }
                userVoicePref.put(from, voiceSwitch);
                client.sendText(from, "（听懂你说：" + voiceText + "）好的，已切换为" + voiceDisplayName(voiceSwitch) + "～");
                return;
            }
            if (isImageGenerationRequest(voiceText)) {
                client.sendText(from, "（听懂你说：" + voiceText + "，正在帮你生成图片）");
                handleImageGeneration(from, voiceText);
                return;
            }

            // LLM 对话
            if (!isLlmEnabled()) {
                client.sendText(from, "我还没配置大模型呢~ 请在 config.properties 里设置 llm.base-url、llm.api-key 和 llm.model，然后重启。");
                return;
            }

            client.sendText(from, "（听懂你说：" + voiceText + "，正在思考并生成语音回复）");

            long t0 = System.currentTimeMillis();
            String llmReply;
            try {
                System.out.println("  [语音→LLM] 正在调用 " + llmModel + " ...");
                llmReply = callLlm(from, voiceText);
                System.out.println("  [语音→LLM] 回复完成（" + (System.currentTimeMillis() - t0) + " ms，" + llmReply.length() + " 字）");
            } catch (Exception e) {
                String errMsg = e.getMessage();
                if (errMsg != null && errMsg.length() > 200) errMsg = errMsg.substring(0, 200) + "...";
                System.out.println("  [语音→LLM] 调用失败: " + errMsg);
                client.sendText(from, "（大模型暂时没响应，请稍后再试）");
                return;
            }

            // ⭐ 重点：文本回复 → 语音 → 发给用户
            replyWithVoice(from, llmReply);
            return;
        }

        // ====== 方式 B：没转出来 → 下载音频文件，然后提示 ======
        // （如果想自己调用第三方 ASR，可以在这里下载音频后调用）
        System.out.println("  [语音识别] ⚠️  微信没返回 text 字段，尝试下载音频...");
        try {
            byte[] voiceData = downloadUserVoice(msg);
            if (voiceData != null && voiceData.length > 0) {
                System.out.println("  [语音识别] 音频已下载：" + voiceData.length + " 字节");
                // 这里如果想接入第三方 ASR（阿里 Paraformer / 百度 / 讯飞）：
                //   String asrText = callAsr(voiceData, voiceItem.getEncode_type(), voiceItem.getSample_rate());
                //   client.sendText(from, "（识别内容：" + asrText + "）");
                client.sendText(from, "（我收到你的语音啦，但这次没听清内容~ 可以打字告诉我哦）");
            } else {
                client.sendText(from, "（没拿到语音内容，请再发一次试试）");
            }
        } catch (Exception e) {
            System.err.println("  [语音识别] 下载音频失败: " + e.getMessage());
            client.sendText(from, "（语音下载失败了，请稍后再试~ 可以打字告诉我哦）");
        }
    }

    private static VoiceItem findVoiceItem(WeixinMessage msg) {
        if (msg.getItem_list() == null) return null;
        for (MessageItem item : msg.getItem_list()) {
            if (item.getVoice_item() != null) return item.getVoice_item();
        }
        return null;
    }

    private static byte[] downloadUserVoice(WeixinMessage msg) throws Exception {
        if (msg.getItem_list() == null) return null;
        for (MessageItem item : msg.getItem_list()) {
            if (item.getVoice_item() != null && item.getVoice_item().getMedia() != null) {
                return client.downloadVoiceFromMessageItem(item);
            }
        }
        return null;
    }

    // ==================== 文本 → 语音回复 ====================

    /**
     * 把 LLM 的文本回答转成语音，然后用微信语音消息发回给用户。
     * 策略：
     *   1. 先尝试 VoiceGeneration.generate(text, imagegenApiKey) —— 用 DashScope CosyVoice
     *   2. 然后尝试 client.sendVoice() 如果失败 → 降级为 client.sendFile()
     *   3. 如果都失败 → 降级为文本消息
     *
     * @param toUserId 接收者
     * @param text     要朗读的文字（LLM 回复）
     */
    private static void replyWithVoice(String toUserId, String text) {
        long t0 = System.currentTimeMillis();

        // 如果文字太长，TTS 可能超长或失败，截断到 500 字左右
        String textForTts = text;
        if (textForTts.length() > 500) {
            textForTts = textForTts.substring(0, 500) + "...";
        }

        try {
            System.out.println("  [TTS] 生成语音中（" + textForTts.length() + " 字）...");

            // Step 1: 调用 TTS 生成语音
            // Key 复用 ttsApiKey（在 loadConfig 里已经优先用 imagegen key）
            String ttsKey = ttsApiKey;
            if (ttsKey == null || ttsKey.isEmpty()) {
                System.out.println("  [TTS] ⚠️  没有 TTS API Key，直接发文本");
                client.sendText(toUserId, text);
                return;
            }

            // 用该用户偏好的音色，没设置则用全局默认
            String voiceName = userVoicePref.getOrDefault(toUserId, ttsVoice);
            VoiceGeneration.VoiceResult voice = VoiceGeneration.generate(
                    textForTts, voiceName, ttsKey, ttsBaseUrl, ttsModel, "mp3");

            // Step 2: 保存成 mp3 文件，以文件形式发给你
            System.out.println("  [TTS] ✅ 准备发送语音文件：" + voice.audioBytes.length
                    + " 字节, 估算时长 " + voice.playTimeMs + "ms");

            // 保存到本地（方便你也能直接打开听）
            String fileName = "tts_" + System.currentTimeMillis() + ".mp3";
            try {
                java.nio.file.Path outPath = java.nio.file.Paths.get(fileName);
                java.nio.file.Files.write(outPath, voice.audioBytes);
                System.out.println("  [TTS] 💾 已保存到：" + outPath.toAbsolutePath());
            } catch (Exception e) {
                System.out.println("  [TTS] 💾 保存本地失败：" + e.getMessage());
            }

            // 直接以文件形式发到微信
            client.sendFile(toUserId,
                    voice.audioBytes,
                    fileName,
                    "（语音回复：" + textForTts.substring(0, Math.min(20, textForTts.length())) + "...）");

            // 额外发一段文字（方便不想听语音的人看）
            // 只有长回复才附文字版，简短语音不冗余
            if (text.length() > 200) {
                try {
                    client.sendText(toUserId, "（文字版：" + text + "）");
                } catch (Exception ignored) {}
            }

            System.out.println("  [TTS] ✅ 语音回复完成（" + (System.currentTimeMillis() - t0) + " ms）");

        } catch (Exception e) {
            // 兜底：语音生成失败，直接发文字
            String errMsg = e.getMessage();
            if (errMsg != null && errMsg.length() > 200) errMsg = errMsg.substring(0, 200) + "...";
            System.out.println("  [TTS] 语音生成失败：" + errMsg + " → 降级为文本回复");
            try {
                client.sendText(toUserId, text);
            } catch (Exception ignored) {}
        }
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
        String modelName = (imagegenModel != null && !imagegenModel.isEmpty()) ? imagegenModel : "wanx-v1";

        // 候选 URL 列表
        String[] urls = new String[]{
            "https://dashscope.aliyuncs.com/api/v1/services/aigc/text2image/image-synthesis",
            "https://dashscope.aliyuncs.com/compatible-mode/v1/images/generations",
            "https://dashscope.aliyuncs.com/api/v1/services/aigc/multimodal-generation/generation",
        };

        // 候选请求体构建：根据 URL 判断用哪种格式（嵌套 / OpenAI 扁平 / multimodal）
        // 实际在循环里按 URL 特征判断构造请求体

        Exception lastError = null;

        java.util.List<String> tryUrls = new java.util.ArrayList<>();
        if (imagegenBaseUrl != null && !imagegenBaseUrl.isEmpty()) {
            tryUrls.add(imagegenBaseUrl);
        }
        for (String u : urls) {
            if (!tryUrls.contains(u)) tryUrls.add(u);
        }

        for (String url : tryUrls) {
            boolean openAiCompatible = url.contains("compatible-mode")
                    || url.contains("images/generations")
                    || url.contains("openai");
            boolean isMultimodal = url.contains("multimodal-generation");

            // 对这个 URL，构造几种不同的请求体尝试
            java.util.List<String> bodies = new java.util.ArrayList<>();

            if (openAiCompatible) {
                // 格式 B：OpenAI 扁平
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("model", modelName);
                body.put("prompt", prompt);
                body.put("size", imageSize.replace('*', 'x'));
                body.put("n", imageN);
                bodies.add(objectMapper.writeValueAsString(body));
            } else if (isMultimodal) {
                // 格式 C/D/E：multimodal 的多种写法
                Map<String, Object> msg = new LinkedHashMap<>();
                msg.put("role", "user");
                msg.put("content", prompt);
                Map<String, Object> inputC = new LinkedHashMap<>();
                inputC.put("messages", new Object[]{msg});
                Map<String, Object> paramsC = new LinkedHashMap<>();
                paramsC.put("size", imageSize.replace('*', 'x'));
                paramsC.put("n", imageN);
                Map<String, Object> bodyC = new LinkedHashMap<>();
                bodyC.put("model", modelName);
                bodyC.put("input", inputC);
                bodyC.put("parameters", paramsC);
                bodies.add(objectMapper.writeValueAsString(bodyC));

                // 同 C，但 size 用 1024*1024（不转 x）
                Map<String, Object> paramsD = new LinkedHashMap<>();
                paramsD.put("size", imageSize);
                paramsD.put("n", imageN);
                Map<String, Object> bodyD = new LinkedHashMap<>();
                bodyD.put("model", modelName);
                bodyD.put("input", inputC);
                bodyD.put("parameters", paramsD);
                bodies.add(objectMapper.writeValueAsString(bodyD));

                // content 是数组形式
                Map<String, Object> textItem = new LinkedHashMap<>();
                textItem.put("type", "text");
                textItem.put("text", prompt);
                Object[] contentArr = new Object[]{textItem};
                Map<String, Object> msgE = new LinkedHashMap<>();
                msgE.put("role", "user");
                msgE.put("content", contentArr);
                Map<String, Object> inputE = new LinkedHashMap<>();
                inputE.put("messages", new Object[]{msgE});
                Map<String, Object> paramsE = new LinkedHashMap<>();
                paramsE.put("size", imageSize);
                paramsE.put("n", imageN);
                Map<String, Object> bodyE = new LinkedHashMap<>();
                bodyE.put("model", modelName);
                bodyE.put("input", inputE);
                bodyE.put("parameters", paramsE);
                bodies.add(objectMapper.writeValueAsString(bodyE));
            } else {
                // 格式 A：旧版 text2image 嵌套 prompt
                Map<String, Object> input = new LinkedHashMap<>();
                input.put("prompt", prompt);
                Map<String, Object> params = new LinkedHashMap<>();
                params.put("size", imageSize);
                params.put("n", imageN);
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("model", modelName);
                body.put("input", input);
                body.put("parameters", params);
                bodies.add(objectMapper.writeValueAsString(body));

                // 也试一下 OpenAI 扁平（有些老模型两种都接受）
                Map<String, Object> bodyFlat = new LinkedHashMap<>();
                bodyFlat.put("model", modelName);
                bodyFlat.put("prompt", prompt);
                bodyFlat.put("size", imageSize.replace('*', 'x'));
                bodyFlat.put("n", imageN);
                bodies.add(objectMapper.writeValueAsString(bodyFlat));
            }

            for (String body : bodies) {
                System.out.println("  [图片生成] 尝试: POST " + url + " | model=" + modelName);
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
                        lastError = new Exception("HTTP " + code + " | " + resp.substring(0, Math.min(200, resp.length())));
                        continue;
                    }

                    JsonNode root = objectMapper.readTree(resp.toString());
                    JsonNode data = root.path("data");
                    if (data.isArray() && data.size() > 0) {
                        JsonNode first = data.get(0);
                        if (first.has("url")) {
                            String imageUrl = first.get("url").asText();
                            System.out.println("  [图片生成] ✅ 图片URL: " + imageUrl.substring(0, Math.min(120, imageUrl.length())) + "...");
                            byte[] imageData = downloadImageFromUrl(imageUrl);
                            return imageData;
                        }
                        if (first.has("b64_json")) {
                            byte[] imageData = Base64.getDecoder().decode(first.get("b64_json").asText(""));
                            return imageData;
                        }
                    }

                    JsonNode output = root.path("output");
                    if (output.has("results") && output.get("results").isArray()) {
                        JsonNode first = output.get("results").get(0);
                        if (first.has("url")) {
                            String imageUrl = first.get("url").asText();
                            System.out.println("  [图片生成] ✅ 图片URL: " + imageUrl.substring(0, Math.min(120, imageUrl.length())) + "...");
                            byte[] imageData = downloadImageFromUrl(imageUrl);
                            return imageData;
                        }
                        if (first.has("b64_image")) {
                            byte[] imageData = Base64.getDecoder().decode(first.get("b64_image").asText(""));
                            return imageData;
                        }
                    }
                    if (output.has("url")) {
                        String imageUrl = output.get("url").asText();
                        byte[] imageData = downloadImageFromUrl(imageUrl);
                        return imageData;
                    }
                    JsonNode choices = output.path("choices");
                    if (choices.isArray() && choices.size() > 0) {
                        JsonNode contentArr = choices.get(0).path("message").path("content");
                        if (contentArr.isArray() && contentArr.size() > 0 && contentArr.get(0).has("image")) {
                            String u2 = contentArr.get(0).get("image").asText().replaceAll("^`|`$", "").trim();
                            byte[] imageData = downloadImageFromUrl(u2);
                            return imageData;
                        }
                    }
                    if (root.has("url")) {
                        byte[] imageData = downloadImageFromUrl(root.get("url").asText());
                        return imageData;
                    }
                    lastError = new Exception("HTTP 200 但解析失败 | " + resp.substring(0, Math.min(200, resp.length())));
                } catch (Exception e) {
                    lastError = e;
                }
            }
        }

        if (lastError != null) throw lastError;
        throw new Exception("图片生成失败，请检查 model / api-key");
    }

    private static byte[] downloadImageFromUrl(String urlString) throws Exception {
        URL url = URI.create(urlString).toURL();
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

    /** 检测文本中是否嵌入了文件内容（如微信把 PDF 当文本发过来） */
    private static boolean isEmbeddedFile(String text) {
        if (text == null || text.length() < 20) return false;
        // PDF 魔术字节 %PDF-
        if (text.startsWith("%PDF-") || text.contains("\n%PDF-") || text.contains("%PDF-1."))
            return true;
        // DOCX 魔术字节 PK (ZIP)
        if (text.length() > 100 && text.charAt(0) == 'P' && text.charAt(1) == 'K')
            return true;
        // 大量乱码字符（高比例非 ASCII + 非中文）
        int nonPrintable = 0, total = Math.min(text.length(), 200);
        for (int i = 0; i < total; i++) {
            char c = text.charAt(i);
            if (c < 32 && c != '\n' && c != '\r' && c != '\t') nonPrintable++;
        }
        return nonPrintable > total / 4;  // 超过 25% 控制字符 = 二进制文件
    }

    /** 从文本中提取嵌入的文件内容并用 LLM 分析 */
    private static void tryProcessEmbeddedFile(String from, String text) throws Exception {
        try {
            // 把 String 转回 bytes（用 ISO-8859-1 无损转换）
            byte[] data = text.getBytes(StandardCharsets.ISO_8859_1);
            String extracted = com.xwc.demo.llm.FileReader.extract(data, "embedded_file");
            System.out.println("  [嵌入文件] 提取 " + extracted.length() + " 字");

            if (extracted.length() < 10) {
                client.sendText(from, "（检测到文件内容，但无法提取文字，可能是扫描版PDF）");
                return;
            }

            String prompt = extracted.length() > 3000
                    ? "总结这份文件内容的要点：\n" + extracted.substring(0, 3000)
                    : "总结这份文件内容的要点：\n" + extracted;
            String reply = callLlm(from, prompt);

            List<LlmMsg> history = userMemoryObj.computeIfAbsent(from, k -> new ArrayList<>());
            history.add(new LlmMsg("user", "帮我看看这个文件"));
            history.add(new LlmMsg("assistant", reply));
            while (history.size() > MEMORY_SIZE * 2) history.remove(0);

            client.sendText(from, reply);
        } catch (Exception e) {
            System.err.println("  [嵌入文件] 解析失败: " + e.getMessage());
            client.sendText(from, "（文件解析失败：" + e.getMessage() + "）");
        }
    }

    /** 检测音色切换指令，返回 "__LIST__" 表示列出音色，返回 null 表示不是指令 */
    private static String detectVoiceCommand(String text) {
        String raw = text.trim();
        // "选择音色"/"音色列表" → 列出
        if (raw.matches(".*(选择音色|音色列表|有哪些音色|音色有哪些|查看音色|切换音色|所有音色|全部音色).*"))
            return "__LIST__";

        // 纯数字选择（1-49）或 "选3"
        if (raw.matches("\\d{1,2}") || raw.matches("选\\d{1,2}")) {
            String num = raw.replaceAll("\\D", "");
            return VOICE_BY_NUM.getOrDefault(num, null);
        }

        // 中文数字（语音输入时 STT 可能输出中文数字）
        String cn = raw.replaceAll("[，。！？\\s~～]", "");
        String num = cnToNum(cn);
        if (num != null) return VOICE_BY_NUM.getOrDefault(num, null);

        String t = raw.replaceAll("[，。！？\\s~～]", "");
        // 编号选择："选3号" "第5个"
        var nm = java.util.regex.Pattern.compile("(?:选|第)(\\d{1,2})(?:号|个)?").matcher(t);
        if (nm.find()) return VOICE_BY_NUM.getOrDefault(nm.group(1), null);

        // 匹配最长别名
        String best = null;
        int bestLen = 0;
        for (var entry : VOICE_BY_NAME.entrySet()) {
            if (t.contains(entry.getKey()) && entry.getKey().length() > bestLen) {
                best = entry.getValue();
                bestLen = entry.getKey().length();
            }
        }
        return best;
    }

    private static String cnToNum(String s) {
        String[] map = {"零","一","二","三","四","五","六","七","八","九","十",
                "十一","十二","十三","十四","十五","十六","十七","十八","十九","二十",
                "二十一","二十二","二十三","二十四","二十五","二十六","二十七","二十八","二十九","三十",
                "三十一","三十二","三十三","三十四","三十五","三十六","三十七","三十八","三十九","四十",
                "四十一","四十二","四十三","四十四","四十五","四十六","四十七","四十八","四十九"};
        for (int i = 0; i < map.length; i++) {
            if (s.equals(map[i]) || s.equals("第" + map[i] + "个") || s.equals("选" + map[i]))
                return String.valueOf(i + 1);
        }
        return null;
    }

    /** 获取音色中文名 */
    private static String voiceDisplayName(String voiceCode) {
        for (String[] v : VOICE_CATALOG) {
            if (v[1].equals(voiceCode)) return v[2] + "(" + v[4] + ")";
        }
        return voiceCode;
    }

    /** 发送音色目录给用户 */
    private static void sendVoiceCatalog(String userId) throws Exception {
        StringBuilder sb = new StringBuilder("🎤 可选音色（共" + VOICE_CATALOG.length + "种）\n");
        sb.append("回复编号或名称即可切换，例如：3 或 选Cherry\n\n");

        String lastCat = "";
        for (String[] v : VOICE_CATALOG) {
            if (!v[4].equals(lastCat)) {
                lastCat = v[4];
                sb.append("【").append(lastCat).append("】\n");
            }
            sb.append(v[0]).append(". ").append(v[2]).append(" — ").append(v[3]).append("\n");
        }
        sb.append("\n💡 也可以直接说\"男声\"\"女声\"\"小孩\"\"老人\"快速切换");

        // 太长分段发
        String text = sb.toString();
        if (text.length() > 4000) {
            client.sendText(userId, text.substring(0, 4000));
            client.sendText(userId, text.substring(4000));
        } else {
            client.sendText(userId, text);
        }
    }

    private static boolean isTtsEnabled() {
        return ttsApiKey != null && !ttsApiKey.isEmpty();
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

            // 语音生成配置（TTS）
            ttsBaseUrl = trim(props.getProperty("tts.base-url", ""));
            ttsApiKey   = trim(props.getProperty("tts.api-key", ""));
            ttsModel = trim(props.getProperty("tts.model", ""));
            ttsVoice = trim(props.getProperty("tts.voice", ""));

            // TTS API Key 优先级: tts.api-key > imagegen.api-key > llm.api-key
            if (ttsApiKey.isEmpty()) ttsApiKey = imagegenApiKey;
            if (ttsApiKey.isEmpty()) ttsApiKey = llmApiKey;

            // 自动推导 TTS URL
            if (ttsBaseUrl.isEmpty() && !llmBaseUrl.isEmpty()) {
                String derived = VoiceGeneration.deriveTtsUrl(llmBaseUrl);
                if (derived != null) ttsBaseUrl = derived;
            }

            if (ttsModel.isEmpty()) ttsModel = "qwen3-tts-flash";
            if (ttsVoice.isEmpty()) ttsVoice = "Cherry";

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
        System.out.println("[语音生成] tts.base-url=" + (ttsBaseUrl.isEmpty() ? "（默认）" : ttsBaseUrl) + " | tts.model=" + (ttsModel.isEmpty() ? "（默认）" : ttsModel) + " | tts.voice=" + ttsVoice);
        System.out.println("----------------------------------------");
    }

    private static String trim(String s) {
        if (s == null) return "";
        s = s.trim();
        // 去掉 # 开头的行内注释（Java Properties 默认不解析这个）
        // 例如："cosyvoice-v3.5 # 你 DashScope 能看到的模型名" → "cosyvoice-v3.5"
        // 注意：URL 里的 # 是合法字符（锚点），但在我们配置里不会有
        int hashIdx = s.indexOf('#');
        if (hashIdx > 0) s = s.substring(0, hashIdx).trim();
        // 去掉 // 开头的行内注释，但**不能把 URL 里的 https:// 误判**
        // 策略：只有 // 前面有空格，或者 // 出现在引号里，才当注释
        // 简单实现：找 " //"（空格+双斜杠），避免误伤 https://
        int commentIdx = s.indexOf(" //");
        if (commentIdx > 0) s = s.substring(0, commentIdx).trim();
        // 也支持行首的 "//"（整行是注释）
        if (s.startsWith("//")) s = "";
        return s;
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
        // Jackson 序列化需要
        public String getRole() { return role; }
        public String getContent() { return content; }
    }

    // ========== 记忆持久化 ==========

    private static final java.io.File MEMORY_FILE = new java.io.File("memory.json");

    /** 启动时加载记忆 */
    private static void loadMemory() {
        if (!MEMORY_FILE.exists()) return;
        try {
            JsonNode root = objectMapper.readTree(MEMORY_FILE);
            int count = 0;
            var it = root.fields();
            while (it.hasNext()) {
                var entry = it.next();
                String userId = entry.getKey();
                JsonNode val = entry.getValue();

                if (val.has("history")) {
                    // 新格式：{ history: [...], voice: "Cherry" }
                    List<LlmMsg> history = new ArrayList<>();
                    for (JsonNode n : val.get("history")) {
                        history.add(new LlmMsg(n.get("role").asText(), n.get("content").asText()));
                    }
                    userMemoryObj.put(userId, history);
                    count += history.size();
                    if (val.has("voice")) {
                        userVoicePref.put(userId, val.get("voice").asText());
                    }
                } else if (val.isArray()) {
                    // 旧格式兼容：直接是消息数组
                    List<LlmMsg> history = new ArrayList<>();
                    for (JsonNode n : val) {
                        history.add(new LlmMsg(n.get("role").asText(), n.get("content").asText()));
                    }
                    userMemoryObj.put(userId, history);
                    count += history.size();
                }
            }
            System.out.println("[记忆] 已加载 " + userMemoryObj.size() + " 个用户，共 " + count + " 条消息"
                    + (userVoicePref.isEmpty() ? "" : "，" + userVoicePref.size() + " 个音色偏好"));
        } catch (Exception e) {
            System.err.println("[记忆] 加载失败: " + e.getMessage());
        }
    }

    /** 退出时保存记忆 */
    private static void saveMemory() {
        if (userMemoryObj.isEmpty() && userVoicePref.isEmpty()) return;
        try {
            Map<String, Map<String, Object>> data = new LinkedHashMap<>();
            for (var entry : userMemoryObj.entrySet()) {
                String uid = entry.getKey();
                Map<String, Object> userData = new LinkedHashMap<>();
                List<Map<String, String>> msgs = new ArrayList<>();
                for (LlmMsg m : entry.getValue()) {
                    Map<String, String> item = new LinkedHashMap<>();
                    item.put("role", m.role);
                    item.put("content", m.content);
                    msgs.add(item);
                }
                userData.put("history", msgs);
                if (userVoicePref.containsKey(uid)) {
                    userData.put("voice", userVoicePref.get(uid));
                }
                data.put(uid, userData);
            }
            // 只存音色偏好没有历史的用户
            for (var entry : userVoicePref.entrySet()) {
                data.putIfAbsent(entry.getKey(), new LinkedHashMap<>() {{
                    put("history", new ArrayList<>());
                    put("voice", entry.getValue());
                }});
            }
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(MEMORY_FILE, data);
            System.out.println("[记忆] 已保存到 " + MEMORY_FILE.getAbsolutePath());
        } catch (Exception e) {
            System.err.println("[记忆] 保存失败: " + e.getMessage());
        }
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