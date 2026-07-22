package com.clawbot.wechatbot.handler;

import com.github.wechat.ilink.sdk.ILinkClient;
import com.github.wechat.ilink.sdk.core.model.MessageItem;
import com.github.wechat.ilink.sdk.core.model.VoiceItem;
import com.github.wechat.ilink.sdk.core.model.WeixinMessage;
import com.clawbot.wechatbot.base.MessageHandler;
import com.clawbot.wechatbot.service.ChatService;
import com.clawbot.wechatbot.service.DocumentService;
import com.clawbot.wechatbot.service.SpeechSynthesisService;
import com.clawbot.wechatbot.util.JsonUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

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

    // ===== 对话记忆：长期摘要 + 最近对话 =====
    // 长期记忆：压缩后的关键信息（"用户叫屿，喜欢聊技术..."）
    // 短期记忆：最近 RECENT_TURNS 轮完整对话
    // 每 SUMMARY_EVERY 轮后，自动调用大模型把近期内容压缩进摘要
    private static final String HISTORY_FILE = "data/chat-history.json";    // 最近对话（JSON）
    private static final String SUMMARY_FILE = "data/memory-summary.txt";   // 长期摘要（纯文本）
    private static final String COUNTER_FILE = "data/turn-counter.txt";     // 累计对话轮数
    private static final int RECENT_TURNS = 15;      // 短期记忆：最近几轮完整保留
    private static final int SUMMARY_EVERY = 10;     // 每多少轮更新一次摘要

    private final ChatService chatService;
    private final SpeechSynthesisService tts;
    private final DocumentService documentService;
    private final StringBuilder longTermSummary = new StringBuilder();
    private final List<String> recentMessages = new ArrayList<>();
    private int turnCounter = 0;
    private final Set<Long> processedMsgIds = new HashSet<>();

    public TextMessageHandler(ChatService chatService) {
        this(chatService, null, null);
    }

    public TextMessageHandler(ChatService chatService, SpeechSynthesisService tts) {
        this(chatService, tts, null);
    }

    public TextMessageHandler(ChatService chatService, SpeechSynthesisService tts, DocumentService documentService) {
        this.chatService = chatService;
        this.tts = tts;
        this.documentService = documentService;
        DocumentService.silencePdfLogs();  // 屏蔽 PDF 库的噪音日志
        loadMemoryFromFile();  // 启动时从磁盘读回：摘要 + 最近对话
    }

    @Override
    public boolean canHandle(WeixinMessage msg) {
        // 有图片的留给 ImageMessageHandler
        if (hasImage(msg)) return false;
        // 空消息不处理
        String text = extractText(msg);
        return text != null && !text.trim().isEmpty();
    }

    @Override
    public void handle(ILinkClient client, WeixinMessage msg) {
        String from = msg.getFrom_user_id();
        String userText = extractText(msg);

        // 去重
        if (msg.getMessage_id() != null) {
            if (!processedMsgIds.add(msg.getMessage_id())) return;
        }

        // 特殊命令
        if (isCommand(userText)) {
            handleCommand(client, from, userText);
            return;
        }

        // 未配置 Key → echo
        if (!chatService.isConfigured()) {
            safeSendText(client, from, "（Echo模式）你说: " + userText
                + "\n提示：配置环境变量 DEEPSEEK_API_KEY 开启智能对话");
            return;
        }

        // DeepSeek 对话
        try {
            // 1. 关键词预处理：
            //    - 语音指令：剥离"语音/读"等词，避免 DeepSeek 自作主张
            //    - 文档指令：先正常对话，把回复写入 PDF/Word
            boolean wantVoice = shouldTriggerTts(userText);
            boolean wantDoc = shouldTriggerDocGen(userText);
            String textForChat = userText;
            if (wantVoice) textForChat = stripTtsKeywords(textForChat);
            if (wantDoc)   textForChat = stripDocKeywords(textForChat);

            // 2. 传给大模型的内容 = 长期摘要 + 最近完整对话
            String context = buildContextForModel();
            String reply = chatService.chat(textForChat, context.isEmpty() ? "" : context);

            // 3. 发送文字回复（清理大模型可能自作主张加的"（用男声）"等标记）
            String textReply = cleanBotReply(reply);
            safeSendText(client, from, textReply);
            appendHistory(userText, textReply);
            System.out.println("[RECV] <" + from + "> " + userText);
            System.out.println("[SEND] " + textReply.replace("\n", " | "));

            // ===== 4. 语音合成（关键词触发）：把回复文字 → WAV 文件发送 =====
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

            // ===== 5. 文档生成（关键词触发）：把回复文字 → PDF/Word 文件发送 =====
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
    public int priority() { return 100; } // 兜底，最后才到

    // ============================================================
    // 工具方法
    // ============================================================

    /**
     * 清理大模型的回复：当用户触发了语音/文档时，大模型可能自作主张在开头加
     *  "（用沉稳的男声）"、"（语音版：）" 这类标记，去掉它们让回复更自然。
     */
    private String cleanBotReply(String reply) {
        if (reply == null) return reply;
        String r = reply.trim();

        // 按顺序尝试去掉前缀（中文括号、英文括号都要考虑）
        String[] prefixPatterns = new String[] {
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
        };
        for (String p : prefixPatterns) {
            r = r.replaceFirst(p, "");
        }

        // 去掉一些常见的开头（"好的，以下是..." 这类倒还自然，这里只去明显的 TTS 标记）
        r = r.trim();
        return r.isEmpty() ? reply : r;
    }

    /**
     * 关键词触发语音生成：
     *   ① 显式语音指令：语音、生成语音、发语音、读、念、朗读、说出来、说给我听
     *   ② 音色指令（隐含要语音）：男声、女声、用男声、用女声、换成男声、换成女声
     */
    private boolean shouldTriggerTts(String userText) {
        if (userText == null) return false;
        String t = userText.trim();
        // 显式语音词
        if (t.contains("语音") || t.contains("读") || t.contains("念")
            || t.contains("朗读") || t.contains("说出来") || t.contains("说给我听")) {
            return true;
        }
        // 音色词（隐含要语音）
        if (t.contains("男声") || t.contains("女声")) return true;
        return false;
    }

    /**
     * 根据用户消息关键词选择音色：
     *   包含"男声" → Ethan（阳光温暖男声，qwen3-tts-flash 官方支持）
     *   包含"女声" → Cherry（女声，默认）
     *   其他情况 → Cherry（默认女声）
     */
    private String pickVoice(String userText) {
        if (userText == null) return "Cherry";
        String t = userText.trim();
        if (t.contains("男声")) return "Ethan";
        if (t.contains("女声")) return "Cherry";
        return "Cherry";
    }

    /**
     * 从用户消息中剥离语音指令关键词，把指令部分去掉，只保留实际内容交给 DeepSeek。
     *
     *   "给我生成一段语音介绍SpringBoot" → "介绍SpringBoot"
     *   "帮我生成一段杭州今天天气怎么样的语音" → "杭州今天天气怎么样的"
     *   "生成一段你好的语音" → "你好的"
     *   "读一下这段代码" → "这段代码"
     *   "用男声生成一段介绍SpringBoot的语音" → "介绍SpringBoot的"
     */
    private String stripTtsKeywords(String userText) {
        if (userText == null) return "";
        String result = userText.trim();

        // ===== 第 1 步：按长短语整体剥离（按长度从长到短，避免先被短的截断） =====
        String[] phrases = new String[] {
            // 音色 + 动作（最长优先）
            "用男声生成一段", "用女声生成一段",
            "用男声生成", "用女声生成",
            "用男声说", "用女声说",
            "用男声读", "用女声读",
            "用男声念", "用女声念",
            "换成男声", "换成女声",
            "换男声", "换女声",
            "用男声", "用女声",
            // "帮我..." 前缀
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
            // 读/念（长的在前）
            "帮我读一下", "给我读一下", "帮我读", "给我读",
            "读一下", "读出来", "读给我听",
            "帮我念一下", "给我念一下", "帮我念", "给我念",
            "念一下", "念出来", "念给我听",
            "帮我朗读一下", "给我朗读一下", "帮我朗读", "给我朗读",
            "朗读一下", "朗读出来", "朗读给我听", "朗读",
            // 说
            "说出来", "说给我听", "帮我说", "给我说",
            // 兜底
            "生成一段",
            "帮我", "给我",
        };

        for (String p : phrases) {
            result = result.replace(p, " ");
        }

        // ===== 第 2 步：逐词清理真正会误导 DeepSeek 的强关键词 =====
        String[] triggerWords = new String[] {
            "语音", "生成", "朗读", "男声", "女声", "说", "念", "读",
        };

        for (String w : triggerWords) {
            result = result.replace(w, " ");
        }

        // 清理多余空格
        result = result.replaceAll("\\s+", " ").trim();
        if (result.isEmpty()) {
            return "你好";
        }
        return result;
    }

    /** 从用户消息中剥离"生成PDF/生成Word/文档"等关键词，
     *  让传给 DeepSeek 的内容更干净，避免它自作主张说"我不能生成PDF"。 */
    private String stripDocKeywords(String userText) {
        if (userText == null) return "你好";
        String result = userText;

        // 常见短语
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

        // 逐词清理
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

    // ============================================================
    // 文档生成
    // ============================================================

    /**
     * 关键词触发生成文档：
     *   生成PDF / 导出PDF / 生成pdf / 生成Word / 导出Word / 生成word / 生成文档
     */
    private boolean shouldTriggerDocGen(String userText) {
        if (userText == null) return false;
        String t = userText.trim().toLowerCase();
        return t.contains("pdf")
            || t.contains("word")
            || t.contains("文档");
    }

    /**
     * 处理文档生成：
     *   1. 判断用户要 PDF 还是 Word
     *   2. 提取文档内容（如果用户写了"生成PDF：xxx内容"，用 xxx；
     *      如果只有"生成PDF"，把最近对话拼起来作为内容）
     *   3. 调用 DocumentService 生成文件 → sendFile 发送
     */
    private void handleDocGen(ILinkClient client, String from, String userText) {
        if (documentService == null) {
            safeSendText(client, from, "⚠️ 文档服务还没配置好，暂时不能生成文档");
            return;
        }

        // 1. 判断文件类型
        String lower = userText.trim().toLowerCase();
        boolean wantPdf = lower.contains("pdf");
        boolean wantWord = lower.contains("word") || lower.contains("文档");
        // 如果两个都没写（只写了"生成文档"），默认 PDF
        if (!wantPdf && !wantWord) wantPdf = true;

        // 2. 提取文档内容：用户写了"生成PDF：xxx" → 用 xxx；否则用最近对话
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

            // 3. 通过 sendFile 发送给用户
            client.sendFile(from, fileBytes, fileName, caption);
            System.out.println("[INFO] ✅ 文档已发送: " + fileName + " (" + fileBytes.length + " bytes)");

            // 4. 把这次指令也算作一轮对话（让记忆更自然）
            appendHistory(userText, "已为你生成" + (wantPdf ? "PDF" : "Word") + "文档");
        } catch (Exception e) {
            System.err.println("[ERROR] 文档生成失败: " + e.getMessage());
            e.printStackTrace();
            safeSendText(client, from, "❌ 生成文档失败：" + e.getMessage());
        }
    }

    /**
     * 从用户消息里提取文档内容：
     *   "生成PDF：介绍一下SpringBoot" → "介绍一下SpringBoot"
     *   "导出Word：最近三天工作总结" → "最近三天工作总结"
     *   "生成PDF"（没写内容）→ 把最近对话拼接起来
     */
    private String extractDocContent(String userText, boolean isDocGen) {
        if (userText == null) return "";
        String original = userText.trim();

        // 尝试按分隔符拆分（冒号、中文冒号、空格）
        String[] splitters = new String[] { "：", ":", " ", "　" };
        for (String sp : splitters) {
            int idx = original.indexOf(sp);
            if (idx > 0 && idx < original.length() - 1) {
                String before = original.substring(0, idx).trim().toLowerCase();
                String after = original.substring(idx + 1).trim();
                // 如果前半部分包含 pdf/word/文档 关键词，后半部分就是内容
                if (before.contains("pdf") || before.contains("word")
                        || before.contains("文档") || before.contains("生成")
                        || before.contains("导出")) {
                    if (!after.isEmpty()) return after;
                }
            }
        }

        // 用户没指定内容 → 把最近对话拼接起来（如果对话为空，给一个默认提示）
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
    }

    /** 生成文档标题（根据用户指令里的内容或日期自动生成） */
    private String buildDocTitle(String userText, boolean wantPdf) {
        // 如果用户写了具体内容（如"生成PDF：介绍SpringBoot"），用内容的前几个字
        String content = extractDocContent(userText, true);
        if (!content.startsWith("【最近对话记录】")
                && !content.startsWith("（暂无对话内容）")) {
            int maxLen = Math.min(20, content.length());
            return content.substring(0, maxLen).trim();
        }
        // 默认标题
        return "聊天对话记录";
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
            longTermSummary.setLength(0);
            recentMessages.clear();
            turnCounter = 0;
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

    // ============================================================
    // 记忆管理（方案C：长期摘要 + 最近对话）
    // ============================================================

    /**
     * 构建传给大模型的 context = 长期摘要 + 最近完整对话
     */
    private String buildContextForModel() {
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
    }

    /**
     * 追加一轮对话，并在必要时触发摘要压缩
     */
    private void appendHistory(String userText, String assistantReply) {
        recentMessages.add("{\"role\":\"user\",\"content\":" + JsonUtils.escape(userText) + "}");
        recentMessages.add("{\"role\":\"assistant\",\"content\":" + JsonUtils.escape(assistantReply) + "}");

        int maxMessages = RECENT_TURNS * 2;
        while (recentMessages.size() > maxMessages) {
            recentMessages.remove(0);
        }

        turnCounter++;
        if (turnCounter > 0 && turnCounter % SUMMARY_EVERY == 0) {
            updateSummaryWithLLM();
        }
        saveMemoryToFile();
    }

    /**
     * 调用大模型把最近对话压缩成摘要，追加到 longTermSummary
     */
    private void updateSummaryWithLLM() {
        try {
            int messagesToSummarize = Math.min(SUMMARY_EVERY * 2, recentMessages.size());
            List<String> toCompress = recentMessages.subList(
                Math.max(0, recentMessages.size() - messagesToSummarize),
                recentMessages.size()
            );
            if (toCompress.isEmpty()) return;

            StringBuilder dialog = new StringBuilder();
            for (String m : toCompress) {
                if (dialog.length() > 0) dialog.append(",");
                dialog.append(m);
            }

            String prompt = "请用简洁中文总结下面的对话，提取关键的长期信息（例如用户姓名、用户偏好、重要约定等），不要重复废话，不要输出客套话，只要纯摘要。如果之前已有摘要，请在之前摘要基础上增量更新，不要完全重写。输出不超过 200 字。";
            String newSummary = chatService.chat(prompt, dialog.toString());

            if (newSummary != null && !newSummary.trim().isEmpty()) {
                if (longTermSummary.length() > 0) {
                    longTermSummary.append("\n");
                }
                longTermSummary.append(newSummary.trim());
                System.out.println("[INFO] 记忆摘要已更新（第 " + turnCounter + " 轮）");
            }
        } catch (Exception e) {
            System.err.println("[WARN] 记忆摘要更新失败（不影响对话）: " + e.getMessage());
        }
    }

    // ============================================================
    // 文件持久化
    // ============================================================

    private void loadMemoryFromFile() {
        try {
            Path path = Paths.get(SUMMARY_FILE);
            if (Files.exists(path)) {
                String content = new String(Files.readAllBytes(path), "UTF-8");
                if (content != null && !content.trim().isEmpty()) {
                    longTermSummary.append(content.trim());
                }
            }
        } catch (IOException ignored) {
            // 摘要文件读取失败不影响，跳过即可
        }

        try {
            Path path = Paths.get(HISTORY_FILE);
            if (Files.exists(path)) {
                String content = new String(Files.readAllBytes(path), "UTF-8");
                if (content != null && !content.trim().isEmpty()) {
                    String trimmed = content.trim();
                    // 去掉外面的 []（标准 JSON 数组格式）
                    if (trimmed.startsWith("[")) {
                        trimmed = trimmed.substring(1);
                    }
                    if (trimmed.endsWith("]")) {
                        trimmed = trimmed.substring(0, trimmed.length() - 1);
                    }
                    // 然后按旧的逗号分隔方式解析
                    parseLegacyFormat(trimmed);
                }
            }
        } catch (IOException ignored) {
            // 对话文件读取失败不影响
        }

        try {
            Path path = Paths.get(COUNTER_FILE);
            if (Files.exists(path)) {
                String content = new String(Files.readAllBytes(path), "UTF-8").trim();
                if (!content.isEmpty()) {
                    turnCounter = Integer.parseInt(content);
                }
            }
        } catch (Exception ignored) {
            // 轮数文件读取失败不影响，从 0 开始
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
            Files.write(Paths.get(SUMMARY_FILE), longTermSummary.toString().getBytes("UTF-8"));
            Files.write(Paths.get(COUNTER_FILE), String.valueOf(turnCounter).getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            sb.append("[\n");
            for (int i = 0; i < recentMessages.size(); i++) {
                if (i > 0) sb.append(",\n");
                sb.append("  ").append(recentMessages.get(i));
            }
            sb.append("\n]");
            Files.write(Paths.get(HISTORY_FILE), sb.toString().getBytes("UTF-8"));
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