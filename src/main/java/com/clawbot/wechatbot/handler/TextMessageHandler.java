package com.clawbot.wechatbot.handler;

import com.github.wechat.ilink.sdk.ILinkClient;
import com.github.wechat.ilink.sdk.core.model.MessageItem;
import com.github.wechat.ilink.sdk.core.model.VoiceItem;
import com.github.wechat.ilink.sdk.core.model.WeixinMessage;
import com.clawbot.wechatbot.base.MessageHandler;
import com.clawbot.wechatbot.service.ChatService;
import com.clawbot.wechatbot.service.DocumentService;
import com.clawbot.wechatbot.service.SpeechSynthesisService;
import com.clawbot.wechatbot.tools.tiannewstool.TianNewsTool;
import com.clawbot.wechatbot.util.JsonUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;

/**
 * 文本消息处理器 —— 处理用户发来的普通文本/语音，调用 DeepSeek 对话
 *
 * 如果配置了 SpeechSynthesisService，会额外生成一份 MP3 音频文件，
 * 通过 sendFile 发送给用户（微信协议不允许机器人发送语音气泡，
 * 但可以发送普通文件）。
 *
 * 注意：这是"兜底" Handler，优先级最低（priority 最大）。
 * 其他 Handler（ImageMessageHandler、ImageGenHandler）先判断，
 * 如果都不处理，最后才进入这里。
 */
public class TextMessageHandler implements MessageHandler {

    private static final String HISTORY_FILE = "data/chat-history.json";
    private static final String SUMMARY_FILE = "data/memory-summary.txt";
    private static final String COUNTER_FILE = "data/turn-counter.txt";
    private static final int RECENT_TURNS = 15;
    private static final int SUMMARY_EVERY = 10;
    private static final int PROCESSED_MSG_IDS_MAX = 10000;
    private static final int SAVE_DEBOUNCE_MS = 2000;

    private final ChatService chatService;
    private final SpeechSynthesisService tts;
    private final DocumentService documentService;
    private final TianNewsTool tianNewsTool;
    private final ScheduledExecutorService backgroundExecutor;

    private final StringBuilder longTermSummary = new StringBuilder();
    private final List<String> recentMessages = new ArrayList<>();
    private final AtomicInteger turnCounter = new AtomicInteger(0);
    private final Set<Long> processedMsgIds = Collections.synchronizedSet(
        Collections.newSetFromMap(new LinkedHashMapLRU<>(PROCESSED_MSG_IDS_MAX)));

    private final ReentrantLock memoryLock = new ReentrantLock();
    private volatile long lastSaveTime = 0;
    private volatile boolean saveScheduled = false;

    private static final Pattern[] CLEAN_PREFIX_PATTERNS = compilePatterns(new String[] {
        "^（用[^\n]{0,15}?声[^\n]{0,5}?）\\s*",
        "^\\(用[^\n]{0,15}?声[^\n]{0,5}?\\)\\s*",
        "^（[^\n]{0,10}?男声[^\n]{0,10}?）\\s*",
        "^（[^\n]{0,10}?女声[^\n]{0,10}?）\\s*",
        "^（[^\n]{0,10}?语音[^\n]{0,10}?）\\s*",
        "^（[^\n]{0,10}?TTS[^\n]{0,10}?）\\s*",
        "^\\([^\n]{0,10}?语音[^\n]{0,10}?\\)\\s*",
        "^【[^\n]{0,10}?语音[^\n]{0,10}?】\\s*",
        "^\"用[^\n]{0,15}?声[^\n]{0,5}?\"\\s*",
        "^语音版[：:]*\\s*",
        "^语音回复[：:]*\\s*",
    });

    private static Pattern[] compilePatterns(String[] regexes) {
        Pattern[] patterns = new Pattern[regexes.length];
        for (int i = 0; i < regexes.length; i++) {
            patterns[i] = Pattern.compile(regexes[i]);
        }
        return patterns;
    }

    private static final class LinkedHashMapLRU<K, V> extends java.util.LinkedHashMap<K, V> {
        private final int maxSize;
        LinkedHashMapLRU(int maxSize) {
            super(16, 0.75f, true);
            this.maxSize = maxSize;
        }
        @Override
        protected boolean removeEldestEntry(java.util.Map.Entry<K, V> eldest) {
            return size() > maxSize;
        }
    }

    public TextMessageHandler(ChatService chatService) {
        this(chatService, null, null, null, null);
    }

    public TextMessageHandler(ChatService chatService, SpeechSynthesisService tts) {
        this(chatService, tts, null, null, null);
    }

    public TextMessageHandler(ChatService chatService, SpeechSynthesisService tts, DocumentService documentService) {
        this(chatService, tts, documentService, null, null);
    }

    public TextMessageHandler(ChatService chatService, SpeechSynthesisService tts,
                              DocumentService documentService, TianNewsTool tianNewsTool,
                              ScheduledExecutorService backgroundExecutor) {
        this.chatService = chatService;
        this.tts = tts;
        this.documentService = documentService;
        this.tianNewsTool = tianNewsTool;
        this.backgroundExecutor = backgroundExecutor;
        DocumentService.silencePdfLogs();
        loadMemoryFromFile();
    }

    @Override
    public boolean canHandle(WeixinMessage msg) {
        if (hasImage(msg)) return false;
        String text = extractText(msg);
        return text != null && !text.trim().isEmpty();
    }

    @Override
    public void handle(ILinkClient client, WeixinMessage msg) {
        String from = msg.getFrom_user_id();
        String userText = extractText(msg);

        if (msg.getMessage_id() != null) {
            if (!processedMsgIds.add(msg.getMessage_id())) return;
        }

        if (isCommand(userText)) {
            handleCommand(client, from, userText);
            return;
        }

        if (!chatService.isConfigured()) {
            safeSendText(client, from, "（Echo模式）你说: " + userText
                + "\n提示：配置环境变量 DEEPSEEK_API_KEY 开启智能对话");
            return;
        }

        try {
            boolean wantVoice = shouldTriggerTts(userText);
            boolean wantDoc = shouldTriggerDocGen(userText);
            String textForChat = userText;
            if (wantVoice) textForChat = stripTtsKeywords(textForChat);
            if (wantDoc)   textForChat = stripDocKeywords(textForChat);

            String newsData = null;
            if (tianNewsTool != null && isNewsQuery(textForChat)) {
                try {
                    String result = tianNewsTool.execute(null);
                    if (result != null && !result.contains("\"success\":false")) {
                        newsData = result;
                    }
                } catch (Exception ignored) {
                }
            }

            String context = buildContextForModel();
            String chatInput;
            if (newsData != null) {
                chatInput = "【以下是最新实时新闻，请据此回答】\n\n"
                    + newsData + "\n\n---\n用户问题：" + textForChat;
            } else {
                chatInput = textForChat;
            }
            String reply = chatService.chat(chatInput, context.isEmpty() ? "" : context);

            String textReply = cleanBotReply(reply);
            safeSendText(client, from, textReply);
            appendHistory(userText, textReply);
            System.out.println("[RECV] <" + from + "> " + userText);
            System.out.println("[SEND] " + textReply.replace("\n", " | "));

            if (tts != null && wantVoice) {
                try {
                    String textForTts = textReply.length() > 200
                        ? textReply.substring(0, 200) : textReply;
                    String voice = pickVoice(userText);
                    byte[] audioBytes = tts.synthesize(textForTts, voice);
                    String fileName = "reply-" + System.currentTimeMillis() + "." + tts.getFileExtension();
                    String caption = "🔊 语音回复（" + textForTts.length() + "字，音色: " + voice + "）";
                    client.sendFile(from, audioBytes, fileName, caption);
                    System.out.println("[INFO] ✅ 语音文件已发送: " + fileName + " (" + audioBytes.length + " bytes)");
                } catch (Exception e2) {
                    System.err.println("[WARN] 语音合成/发送失败: " + e2.getMessage());
                }
            }

            if (documentService != null && wantDoc) {
                try {
                    boolean isPdf = userText.toLowerCase().contains("pdf");
                    byte[] fileBytes = isPdf
                        ? documentService.createPdf("回复内容", textReply)
                        : documentService.createWord("回复内容", textReply);
                    String fileName = "bot-" + System.currentTimeMillis() + (isPdf ? ".pdf" : ".docx");
                    String caption = "📄 " + (isPdf ? "PDF" : "Word") + " 文档（" + textReply.length() + "字）";
                    client.sendFile(from, fileBytes, fileName, caption);
                    System.out.println("[INFO] ✅ 文档已发送: " + fileName + " (" + fileBytes.length + " bytes)");
                } catch (Exception e2) {
                    System.err.println("[WARN] 文档生成/发送失败: " + e2.getMessage());
                    safeSendText(client, from, "（文档生成失败，但上面的文字回复已经发了～）");
                }
            }
        } catch (Exception e) {
            System.err.println("[ERROR] DeepSeek: " + e.getMessage());
            safeSendText(client, from, "抱歉，大脑暂时短路了：" + e.getMessage());
        }
    }

    @Override
    public int priority() { return 100; }

    private String cleanBotReply(String reply) {
        if (reply == null) return reply;
        String r = reply.trim();
        for (Pattern p : CLEAN_PREFIX_PATTERNS) {
            r = p.matcher(r).replaceFirst("");
        }
        r = r.trim();
        return r.isEmpty() ? reply : r;
    }

    private boolean shouldTriggerTts(String userText) {
        if (userText == null) return false;
        String t = userText.trim();
        if (t.contains("语音") || t.contains("读") || t.contains("念")
            || t.contains("朗读") || t.contains("说出来") || t.contains("说给我听")) {
            return true;
        }
        if (t.contains("男声") || t.contains("女声")) return true;
        return false;
    }

    private String pickVoice(String userText) {
        if (userText == null) return "Cherry";
        String t = userText.trim();
        if (t.contains("男声")) return "Ethan";
        if (t.contains("女声")) return "Cherry";
        return "Cherry";
    }

    private String stripTtsKeywords(String userText) {
        if (userText == null) return "";
        String result = userText.trim();

        String[] phrases = new String[] {
            "用男声生成一段", "用女声生成一段",
            "用男声生成", "用女声生成",
            "用男声说", "用女声说",
            "用男声读", "用女声读",
            "用男声念", "用女声念",
            "换成男声", "换成女声",
            "换男声", "换女声",
            "用男声", "用女声",
            "帮我生成一段语音", "给我生成一段语音",
            "帮我生成语音", "给我生成语音",
            "帮我生成一段", "给我生成一段",
            "帮我生成", "给我生成",
            "帮我发语音", "给我发语音",
            "帮我语音", "给我语音",
            "用语音",
            "发语音", "生成语音",
            "语音版的", "语音版",
            "语音回复", "语音回答",
            "语音介绍",
            "帮我读一下", "给我读一下", "帮我读", "给我读",
            "读一下", "读出来", "读给我听",
            "帮我念一下", "给我念一下", "帮我念", "给我念",
            "念一下", "念出来", "念给我听",
            "帮我朗读一下", "给我朗读一下", "帮我朗读", "给我朗读",
            "朗读一下", "朗读出来", "朗读给我听", "朗读",
            "说出来", "说给我听", "帮我说", "给我说",
            "生成一段",
            "帮我", "给我",
        };

        for (String p : phrases) {
            result = result.replace(p, " ");
        }

        String[] triggerWords = new String[] {
            "语音", "生成", "朗读", "男声", "女声", "说", "念", "读",
        };

        for (String w : triggerWords) {
            result = result.replace(w, " ");
        }

        result = result.replaceAll("\\s+", " ").trim();
        if (result.isEmpty()) {
            return "你好";
        }
        return result;
    }

    private String stripDocKeywords(String userText) {
        if (userText == null) return "你好";
        String result = userText;

        String[] phrases = new String[] {
            "生成PDF", "生成pdf", "生成PDF文件",
            "导出PDF", "导出pdf", "生成Word", "生成word",
            "导出Word", "导出word", "生成文档", "导出文档",
            "用PDF", "用Word", "写成PDF", "写成Word",
            "帮我生成PDF", "帮我生成Word", "帮我生成文档",
        };
        for (String p : phrases) {
            result = result.replace(p, " ");
        }

        String[] triggerWords = new String[] {
            "PDF", "pdf", "Word", "word", "文档", "生成", "导出",
        };
        for (String w : triggerWords) {
            result = result.replace(w, " ");
        }

        result = result.replaceAll("\\s+", " ").trim();
        if (result.isEmpty()) return "你好";
        return result;
    }

    private boolean shouldTriggerDocGen(String userText) {
        if (userText == null) return false;
        String t = userText.trim().toLowerCase();
        return t.contains("pdf")
            || t.contains("word")
            || t.contains("文档");
    }

    private void handleDocGen(ILinkClient client, String from, String userText) {
        if (documentService == null) {
            safeSendText(client, from, "⚠️ 文档服务还没配置好，暂时不能生成文档");
            return;
        }

        String lower = userText.trim().toLowerCase();
        boolean wantPdf = lower.contains("pdf");
        boolean wantWord = lower.contains("word") || lower.contains("文档");
        if (!wantPdf && !wantWord) wantPdf = true;

        String content = extractDocContent(userText, wantPdf || wantWord);
        String title = buildDocTitle(userText, wantPdf);

        safeSendText(client, from, "📝 正在为你生成" + (wantPdf ? "PDF" : "Word")
                + "文档（" + content.length() + "字）...");

        try {
            byte[] fileBytes;
            String fileName;
            String caption;
            if (wantPdf) {
                fileBytes = documentService.createPdf(title, content);
                fileName = "bot-" + System.currentTimeMillis() + ".pdf";
                caption = "📄 " + title + "（PDF，" + fileBytes.length + "字节）";
            } else {
                fileBytes = documentService.createWord(title, content);
                fileName = "bot-" + System.currentTimeMillis() + ".docx";
                caption = "📄 " + title + "（Word，" + fileBytes.length + "字节）";
            }

            client.sendFile(from, fileBytes, fileName, caption);
            System.out.println("[INFO] ✅ 文档已发送: " + fileName + " (" + fileBytes.length + " bytes)");

            appendHistory(userText, "已为你生成" + (wantPdf ? "PDF" : "Word") + "文档");
        } catch (Exception e) {
            System.err.println("[ERROR] 文档生成失败: " + e.getMessage());
            e.printStackTrace();
            safeSendText(client, from, "❌ 生成文档失败：" + e.getMessage());
        }
    }

    private String extractDocContent(String userText, boolean isDocGen) {
        if (userText == null) return "";
        String original = userText.trim();

        String[] splitters = new String[] { "：", ":", " ", "　" };
        for (String sp : splitters) {
            int idx = original.indexOf(sp);
            if (idx > 0 && idx < original.length() - 1) {
                String before = original.substring(0, idx).trim().toLowerCase();
                String after = original.substring(idx + 1).trim();
                if (before.contains("pdf") || before.contains("word")
                        || before.contains("文档") || before.contains("生成")
                        || before.contains("导出")) {
                    if (!after.isEmpty()) return after;
                }
            }
        }

        memoryLock.lock();
        try {
            if (recentMessages.isEmpty()) {
                return "（暂无对话内容，试着和我聊几句，然后再生成文档吧～）";
            }

            StringBuilder sb = new StringBuilder();
            sb.append("【最近对话记录】\n\n");
            for (int i = 0; i < recentMessages.size(); i += 2) {
                String userMsg = recentMessages.get(i);
                String botReply = (i + 1 < recentMessages.size())
                        ? recentMessages.get(i + 1) : "（无回复）";
                sb.append("用户: ").append(userMsg).append("\n");
                sb.append("助手: ").append(botReply).append("\n\n");
            }
            return sb.toString();
        } finally {
            memoryLock.unlock();
        }
    }

    private String buildDocTitle(String userText, boolean wantPdf) {
        String content = extractDocContent(userText, true);
        if (!content.startsWith("【最近对话记录】")
                && !content.startsWith("（暂无对话内容）")) {
            int maxLen = Math.min(20, content.length());
            return content.substring(0, maxLen).trim();
        }
        return "聊天对话记录";
    }

    private boolean isNewsQuery(String text) {
        if (text == null || text.isEmpty()) return false;
        String t = text.trim().toLowerCase();
        String[] keywords = {"新闻", "大事", "热点", "最近", "发生了什么",
            "有什么", "头条", "资讯", "时事", "今天", "昨天",
            "科技", "体育", "娱乐", "财经", "国际", "社会"};
        for (String kw : keywords) {
            if (t.contains(kw)) return true;
        }
        return false;
    }

    private boolean isCommand(String text) {
        String t = text.trim().toLowerCase();
        return t.equals("help") || t.equals("帮助") || t.equals("?")
            || t.equals("clear") || t.equals("清空") || t.equals("重置");
    }

    private void handleCommand(ILinkClient client, String from, String text) {
        String t = text.trim().toLowerCase();
        if (t.equals("help") || t.equals("帮助") || t.equals("?")) {
            safeSendText(client, from,
                "我可以做的事情："
                + "\n1. 文本对话（接入 DeepSeek 大模型）"
                + "\n2. 看图识别（发送图片即可，接入阿里云百炼视觉模型）"
                + "\n3. 文生图（说「画图 一只在月球上的猫」即可生成图片）"
                + "\n4. 语音回复（消息里包含「语音/读/念」等关键词，我会额外发送语音文件）"
                + "\n（发送 'clear' 可清空对话记忆）");
            return;
        }
        if (t.equals("clear") || t.equals("清空") || t.equals("重置")) {
            memoryLock.lock();
            try {
                longTermSummary.setLength(0);
                recentMessages.clear();
                turnCounter.set(0);
            } finally {
                memoryLock.unlock();
            }
            deleteHistoryFile();
            deleteSummaryFile();
            safeSendText(client, from, "对话记忆已清空（包括长期摘要），我们重新开始聊天吧！");
            return;
        }
    }

    private boolean hasImage(WeixinMessage msg) {
        if (msg.getItem_list() == null) return false;
        for (MessageItem item : msg.getItem_list()) {
            if (item.getImage_item() != null) return true;
        }
        return false;
    }

    private String extractText(WeixinMessage msg) {
        if (msg.getItem_list() == null) return null;
        StringBuilder sb = new StringBuilder();
        for (MessageItem item : msg.getItem_list()) {
            if (item.getType() == 1 && item.getText_item() != null) {
                sb.append(item.getText_item().getText());
            } else if (item.getImage_item() != null) {
                sb.append("[图片]");
            } else if (item.getVoice_item() != null) {
                VoiceItem v = item.getVoice_item();
                if (v.getText() != null && !v.getText().isEmpty()) {
                    sb.append(v.getText());
                } else {
                    sb.append("[语音]");
                }
            } else if (item.getFile_item() != null) {
                sb.append("[文件]");
            } else if (item.getVideo_item() != null) {
                sb.append("[视频]");
            }
        }
        return sb.toString();
    }

    private void safeSendText(ILinkClient client, String to, String text) {
        try {
            long typingMillis = Math.min(2000, 300L + text.length() * 20L);
            client.sendTextWithTyping(to, text, typingMillis);
        } catch (Exception e) {
            System.err.println("[ERROR] 发送失败: " + e.getMessage());
        }
    }

    private String buildContextForModel() {
        memoryLock.lock();
        try {
            StringBuilder sb = new StringBuilder();
            if (longTermSummary.length() > 0) {
                sb.append("{\"role\":\"system\",\"content\":")
                  .append(JsonUtils.escape("【长期记忆摘要】\n" + longTermSummary.toString()))
                  .append("}");
            }
            for (String msg : recentMessages) {
                if (sb.length() > 0) sb.append(",");
                sb.append(msg);
            }
            return sb.toString();
        } finally {
            memoryLock.unlock();
        }
    }

    private void appendHistory(String userText, String assistantReply) {
        final boolean needSummaryUpdate;
        memoryLock.lock();
        try {
            recentMessages.add("{\"role\":\"user\",\"content\":" + JsonUtils.escape(userText) + "}");
            recentMessages.add("{\"role\":\"assistant\",\"content\":" + JsonUtils.escape(assistantReply) + "}");

            int maxMessages = RECENT_TURNS * 2;
            while (recentMessages.size() > maxMessages) {
                recentMessages.remove(0);
            }

            int turn = turnCounter.incrementAndGet();
            needSummaryUpdate = (turn > 0 && turn % SUMMARY_EVERY == 0);
        } finally {
            memoryLock.unlock();
        }

        scheduleSaveMemory();

        if (needSummaryUpdate && backgroundExecutor != null) {
            backgroundExecutor.execute(this::updateSummaryWithLLM);
        }
    }

    private void scheduleSaveMemory() {
        long now = System.currentTimeMillis();
        if (now - lastSaveTime < SAVE_DEBOUNCE_MS) {
            if (!saveScheduled && backgroundExecutor != null) {
                saveScheduled = true;
                backgroundExecutor.schedule(() -> {
                    saveScheduled = false;
                    lastSaveTime = System.currentTimeMillis();
                    saveMemoryToFile();
                }, SAVE_DEBOUNCE_MS, TimeUnit.MILLISECONDS);
            }
            return;
        }
        lastSaveTime = now;
        if (backgroundExecutor != null) {
            backgroundExecutor.execute(this::saveMemoryToFile);
        } else {
            saveMemoryToFile();
        }
    }

    private void updateSummaryWithLLM() {
        try {
            StringBuilder dialog = new StringBuilder();
            memoryLock.lock();
            try {
                int messagesToSummarize = Math.min(SUMMARY_EVERY * 2, recentMessages.size());
                List<String> toCompress = messagesToSummarize >= recentMessages.size()
                    ? recentMessages
                    : recentMessages.subList(
                        recentMessages.size() - messagesToSummarize,
                        recentMessages.size());
                if (toCompress.isEmpty()) return;

                for (String m : toCompress) {
                    if (dialog.length() > 0) dialog.append(",");
                    dialog.append(m);
                }
            } finally {
                memoryLock.unlock();
            }

            String prompt = "请用简洁中文总结下面的对话，提取关键的长期信息（例如用户姓名、用户偏好、重要约定等），不要重复废话，不要输出客套话，只要纯摘要。如果之前已有摘要，请在之前摘要基础上增量更新，不要完全重写。输出不超过 200 字。";
            String newSummary = chatService.chat(prompt, dialog.toString());

            if (newSummary != null && !newSummary.trim().isEmpty()) {
                memoryLock.lock();
                try {
                    if (longTermSummary.length() > 0) {
                        longTermSummary.append("\n");
                    }
                    longTermSummary.append(newSummary.trim());
                } finally {
                    memoryLock.unlock();
                }
                System.out.println("[INFO] 记忆摘要已更新（第 " + turnCounter.get() + " 轮）");
                scheduleSaveMemory();
            }
        } catch (Exception e) {
            System.err.println("[WARN] 记忆摘要更新失败（不影响对话）: " + e.getMessage());
        }
    }

    private void loadMemoryFromFile() {
        try {
            Path path = Paths.get(SUMMARY_FILE);
            if (Files.exists(path)) {
                String content = new String(Files.readAllBytes(path), "UTF-8");
                if (content != null && !content.trim().isEmpty()) {
                    memoryLock.lock();
                    try {
                        longTermSummary.append(content.trim());
                    } finally {
                        memoryLock.unlock();
                    }
                }
            }
        } catch (IOException ignored) {
        }

        try {
            Path path = Paths.get(HISTORY_FILE);
            if (Files.exists(path)) {
                String content = new String(Files.readAllBytes(path), "UTF-8");
                if (content != null && !content.trim().isEmpty()) {
                    String trimmed = content.trim();
                    if (trimmed.startsWith("[")) {
                        trimmed = trimmed.substring(1);
                    }
                    if (trimmed.endsWith("]")) {
                        trimmed = trimmed.substring(0, trimmed.length() - 1);
                    }
                    memoryLock.lock();
                    try {
                        parseLegacyFormat(trimmed);
                    } finally {
                        memoryLock.unlock();
                    }
                }
            }
        } catch (IOException ignored) {
        }

        try {
            Path path = Paths.get(COUNTER_FILE);
            if (Files.exists(path)) {
                String content = new String(Files.readAllBytes(path), "UTF-8").trim();
                if (!content.isEmpty()) {
                    turnCounter.set(Integer.parseInt(content));
                }
            }
        } catch (Exception ignored) {
        }
    }

    private void parseLegacyFormat(String content) {
        int idx = 0;
        while (idx < content.length()) {
            int start = content.indexOf("{\"role\"", idx);
            if (start < 0) break;
            int nextStart = content.indexOf(",{\"role\"", start + 1);
            int end = (nextStart > 0) ? nextStart : content.length();
            String piece = content.substring(start, end).trim();
            if (piece.endsWith(",")) piece = piece.substring(0, piece.length() - 1);
            recentMessages.add(piece);
            idx = end + 1;
        }
    }

    private void saveMemoryToFile() {
        try {
            Path dataDir = Paths.get("data");
            if (!Files.exists(dataDir)) {
                Files.createDirectories(dataDir);
            }
            memoryLock.lock();
            try {
                Files.write(Paths.get(SUMMARY_FILE), longTermSummary.toString().getBytes("UTF-8"));
                Files.write(Paths.get(COUNTER_FILE), String.valueOf(turnCounter.get()).getBytes("UTF-8"));
                StringBuilder sb = new StringBuilder();
                sb.append("[\n");
                for (int i = 0; i < recentMessages.size(); i++) {
                    if (i > 0) sb.append(",\n");
                    sb.append("  ").append(recentMessages.get(i));
                }
                sb.append("\n]");
                Files.write(Paths.get(HISTORY_FILE), sb.toString().getBytes("UTF-8"));
            } finally {
                memoryLock.unlock();
            }
        } catch (IOException e) {
            System.err.println("[WARN] 保存记忆文件失败: " + e.getMessage());
        }
    }

    private void deleteHistoryFile() {
        try {
            Path path = Paths.get(HISTORY_FILE);
            if (Files.exists(path)) {
                Files.delete(path);
            }
        } catch (IOException e) {
            System.err.println("[WARN] 删除对话文件失败: " + e.getMessage());
        }
    }

    private void deleteSummaryFile() {
        try {
            Path path = Paths.get(SUMMARY_FILE);
            if (Files.exists(path)) {
                Files.delete(path);
            }
        } catch (IOException e) {
            System.err.println("[WARN] 删除摘要文件失败: " + e.getMessage());
        }
    }
}