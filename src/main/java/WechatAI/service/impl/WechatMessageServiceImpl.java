package WechatAI.service.impl;

import WechatAI.model.VoiceReply;
import WechatAI.model.ChatMessage;
import WechatAI.service.AiChatService;
import WechatAI.service.DocumentAnalysisService;
import WechatAI.service.ImageGenerationService;
import WechatAI.service.ImageUnderstandingService;
import WechatAI.service.MemoryService;
import WechatAI.service.MessageTextExtractor;
import WechatAI.service.SpeechRecognitionService;
import WechatAI.service.SpeechSynthesisService;
import WechatAI.service.WechatMessageService;
import WechatAI.support.MessageItemUtils;
import WechatAI.support.TextIntentUtils;
import WechatAI.support.VoiceCatalog;
import WechatAI.support.WechatClientGateway;
import com.github.wechat.ilink.sdk.core.model.MessageItem;
import com.github.wechat.ilink.sdk.core.model.WeixinMessage;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 微信消息业务编排实现，统一调度文本、图片、文档、语音和记忆能力。
 */
public class WechatMessageServiceImpl implements WechatMessageService {

    private static final String FALLBACK_REPLY = "抱歉，我现在有点忙，稍后再回复你哦~";
    private static final String IMAGE_FALLBACK_REPLY = "抱歉，我现在还没能处理这张图片，请稍后再试。";
    private static final String IMAGE_GENERATION_FALLBACK_REPLY = "抱歉，图片生成失败了。请确认 qwen-image 已在阿里云百炼开通额度，并且 config.properties 里已填写 qwen.api.key。";
    private static final String DOCUMENT_ANALYSIS_FALLBACK_REPLY = "抱歉，我现在还没能分析这份文档，请确认文件是 PDF、DOC 或 DOCX。";
    private static final String SPEECH_RECOGNITION_FALLBACK_REPLY = "抱歉，我现在还没能识别这段语音，请稍后再试。";
    private static final String SPEECH_SYNTHESIS_FALLBACK_REPLY = "抱歉，我现在还没能生成语音，请稍后再试。";
    private static final long SLOW_STEP_WARN_MS = 3000L;

    private final WechatClientGateway wechatClient;
    private final AiChatService aiChatService;
    private final DocumentAnalysisService documentAnalysisService;
    private final ImageUnderstandingService imageUnderstandingService;
    private final ImageGenerationService imageGenerationService;
    private final SpeechRecognitionService speechRecognitionService;
    private final SpeechSynthesisService speechSynthesisService;
    private final MessageTextExtractor textExtractor;
    private final MemoryService memoryService;
    private final Map<String, String> userVoicePreferences = new ConcurrentHashMap<>();

    public WechatMessageServiceImpl(
            WechatClientGateway wechatClient,
            AiChatService aiChatService,
            DocumentAnalysisService documentAnalysisService,
            ImageUnderstandingService imageUnderstandingService,
            ImageGenerationService imageGenerationService,
            SpeechRecognitionService speechRecognitionService,
            SpeechSynthesisService speechSynthesisService,
            MessageTextExtractor textExtractor,
            MemoryService memoryService
    ) {
        this.wechatClient = wechatClient;
        this.aiChatService = aiChatService;
        this.documentAnalysisService = documentAnalysisService;
        this.imageUnderstandingService = imageUnderstandingService;
        this.imageGenerationService = imageGenerationService;
        this.speechRecognitionService = speechRecognitionService;
        this.speechSynthesisService = speechSynthesisService;
        this.textExtractor = textExtractor;
        this.memoryService = memoryService;
    }

    @Override
    public void handleAndReply(WeixinMessage message) {
        String fromUserId = message.getFrom_user_id();
        TypingIndicator typingIndicator = null;
        StepTimer timer = new StepTimer();
        try {
            String receivedText = textExtractor.extract(message);
            timer.mark("提取文本");

            System.out.println("💬 收到来自 " + fromUserId + " 的消息: " + receivedText);
            typingIndicator = startTypingIndicator(fromUserId);
            timer.mark("开启正在输入");

            if (handleImageMessage(message, receivedText, fromUserId)) {
                timer.mark("图片消息处理");
                return;
            }
            if (handleDocumentMessage(message, receivedText, fromUserId)) {
                timer.mark("文档消息处理");
                return;
            }
            if (handleVoiceMessage(message, fromUserId)) {
                timer.mark("语音消息处理");
                return;
            }

            if (receivedText == null || receivedText.trim().isEmpty()) {
                System.out.println("⏭️ 消息为空，跳过回复");
                return;
            }

            if (TextIntentUtils.isClearMemoryRequest(receivedText)) {
                memoryService.clear(fromUserId);
                timer.mark("清空短期记忆");
                sendTextReply(fromUserId, "好的，我已经清空这段对话记忆了。");
                timer.mark("发送文本回复");
                return;
            }

            if (TextIntentUtils.isVoicePreferenceRequest(receivedText)) {
                String selectedVoice = VoiceCatalog.resolveVoice(receivedText, currentVoice(fromUserId));
                userVoicePreferences.put(fromUserId, selectedVoice);
                sendTextReply(fromUserId, "好的，后续语音会使用" + VoiceCatalog.displayName(selectedVoice) + "。");
                timer.mark("切换音色并回复");
                return;
            }

            if (TextIntentUtils.isSpeechSynthesisRequest(receivedText)) {
                handleSpeechSynthesis(fromUserId, receivedText);
                timer.mark("语音生成请求处理");
                return;
            }

            if (TextIntentUtils.isImageGenerationRequest(receivedText)) {
                handleImageGeneration(fromUserId, receivedText);
                timer.mark("图片生成请求处理");
                return;
            }

            String replyText = chatWithMemory(fromUserId, receivedText);
            timer.mark("大模型对话和短期记忆");
            if (replyText == null || replyText.isEmpty()) {
                replyText = FALLBACK_REPLY;
            }

            sendTextReply(fromUserId, replyText);
            timer.mark("发送文本回复");
            System.out.println("✅ 回复已发送: " + replyText);
        } catch (Exception e) {
            System.err.println("❌ 处理消息失败: " + e.getMessage());
            e.printStackTrace();
        } finally {
            if (typingIndicator != null) {
                typingIndicator.close();
                timer.mark("关闭正在输入");
            }
            System.out.println("⏱️ 消息业务总耗时: " + timer.totalMillis() + "ms");
        }
    }

    private boolean handleImageMessage(WeixinMessage message, String prompt, String fromUserId) throws Exception {
        MessageItem imageItem = MessageItemUtils.firstImageItem(message);
        if (imageItem == null) {
            return false;
        }

        System.out.println("🖼️ 收到图片消息，开始下载并理解图片");
        byte[] imageBytes = downloadImage(imageItem);
        String replyText = imageUnderstandingService.understand(imageBytes, prompt);
        if (replyText == null || replyText.isEmpty()) {
            replyText = IMAGE_FALLBACK_REPLY;
        }

        sendTextReply(fromUserId, replyText);
        System.out.println("✅ 图片理解回复已发送: " + replyText);
        return true;
    }

    private boolean handleDocumentMessage(WeixinMessage message, String prompt, String fromUserId) throws Exception {
        MessageItem fileItem = MessageItemUtils.firstFileItem(message);
        if (fileItem == null) {
            return false;
        }

        String fileName = fileItem.getFile_item() == null ? "" : fileItem.getFile_item().getFile_name();
        if (!documentAnalysisService.supports(fileName)) {
            System.out.println("📄 收到非文档文件，跳过文档分析: " + fileName);
            return false;
        }

        System.out.println("📄 收到文档，开始下载并分析: " + fileName);
        byte[] fileBytes = downloadFile(fileItem);
        String replyText = documentAnalysisService.analyze(fileBytes, fileName, prompt);
        if (replyText == null || replyText.trim().isEmpty()) {
            String errorMessage = documentAnalysisService.getLastErrorMessage();
            replyText = errorMessage == null || errorMessage.isEmpty()
                    ? DOCUMENT_ANALYSIS_FALLBACK_REPLY
                    : "抱歉，文档分析失败了：" + errorMessage;
        }

        sendTextReply(fromUserId, replyText);
        System.out.println("✅ 文档分析结果已发送");
        return true;
    }

    private boolean handleVoiceMessage(WeixinMessage message, String fromUserId) throws Exception {
        MessageItem voiceItem = MessageItemUtils.firstVoiceItem(message);
        if (voiceItem == null) {
            return false;
        }

        System.out.println("🎙️ 收到语音消息，开始下载并识别");
        byte[] voiceBytes = downloadVoice(voiceItem);
        Integer sampleRate = voiceItem.getVoice_item().getSample_rate();
        Integer encodeType = voiceItem.getVoice_item().getEncode_type();
        System.out.println("🎙️ 语音元信息: bytes=" + voiceBytes.length
                + ", encodeType=" + encodeType
                + ", sampleRate=" + sampleRate
                + ", playtime=" + voiceItem.getVoice_item().getPlaytime());
        String recognizedText = speechRecognitionService.recognize(voiceBytes, "wechat-voice.amr", null, sampleRate);
        if (recognizedText == null || recognizedText.trim().isEmpty()) {
            String errorMessage = speechRecognitionService.getLastErrorMessage();
            String replyText = errorMessage == null || errorMessage.isEmpty()
                    ? SPEECH_RECOGNITION_FALLBACK_REPLY
                    : "抱歉，语音识别失败了：" + errorMessage;
            sendTextReply(fromUserId, replyText);
            return true;
        }

        System.out.println("✅ 语音识别结果: " + recognizedText);
        if (TextIntentUtils.isSpeechSynthesisRequest(recognizedText)) {
            handleSpeechSynthesis(fromUserId, recognizedText);
            return true;
        }

        String replyText = chatWithMemory(fromUserId, recognizedText);
        if (replyText == null || replyText.isEmpty()) {
            replyText = FALLBACK_REPLY;
        }

        if (!sendVoiceReply(fromUserId, replyText, currentVoice(fromUserId))) {
            sendTextReply(fromUserId, replyText);
        }
        return true;
    }

    private void handleImageGeneration(String fromUserId, String receivedText) throws Exception {
        String prompt = TextIntentUtils.normalizeImagePrompt(receivedText);
        System.out.println("🎨 收到图片生成请求: " + prompt);
        byte[] imageBytes = imageGenerationService.generate(prompt);

        if (imageBytes == null || imageBytes.length == 0) {
            String errorMessage = imageGenerationService.getLastErrorMessage();
            String replyText = errorMessage == null || errorMessage.isEmpty()
                    ? IMAGE_GENERATION_FALLBACK_REPLY
                    : "抱歉，图片生成失败了：" + errorMessage;
            sendTextReply(fromUserId, replyText);
            return;
        }

        sendImage(fromUserId, imageBytes, "generated-image.png", "image/png");
        System.out.println("✅ 生成图片已发送");
    }

    private void handleSpeechSynthesis(String fromUserId, String rawText) throws Exception {
        String selectedVoice = VoiceCatalog.resolveVoice(rawText, currentVoice(fromUserId));
        userVoicePreferences.put(fromUserId, selectedVoice);
        String text = TextIntentUtils.normalizeSpeechPrompt(rawText);
        System.out.println("🔊 准备生成语音内容: " + text + "，音色: " + selectedVoice);
        if (!sendVoiceReply(fromUserId, text, selectedVoice)) {
            String errorMessage = speechSynthesisService.getLastErrorMessage();
            String replyText = errorMessage == null || errorMessage.isEmpty()
                    ? SPEECH_SYNTHESIS_FALLBACK_REPLY
                    : "抱歉，语音生成失败了：" + errorMessage;
            sendTextReply(fromUserId, replyText);
        }
    }

    private boolean sendVoiceReply(String fromUserId, String text, String voice) throws Exception {
        VoiceReply voiceReply = speechSynthesisService.synthesize(text, voice);
        if (voiceReply == null || voiceReply.getAudioBytes() == null || voiceReply.getAudioBytes().length == 0) {
            return false;
        }

        sendFile(fromUserId, voiceReply.getAudioBytes(), voiceReply.getFileName(), voiceReply.getMimeType());
        System.out.println("✅ 语音MP3文件已发送");
        return true;
    }

    private void sendTextReply(String fromUserId, String text) throws Exception {
        wechatClient.sendText(fromUserId, text);
    }

    private void sendImage(String fromUserId, byte[] imageBytes, String fileName, String mimeType) throws Exception {
        wechatClient.sendImage(fromUserId, imageBytes, fileName, mimeType);
    }

    private void sendFile(String fromUserId, byte[] fileBytes, String fileName, String mimeType) throws Exception {
        wechatClient.sendFile(fromUserId, fileBytes, fileName, mimeType);
    }

    private byte[] downloadImage(MessageItem imageItem) throws Exception {
        return wechatClient.downloadImage(imageItem);
    }

    private byte[] downloadVoice(MessageItem voiceItem) throws Exception {
        return wechatClient.downloadVoice(voiceItem);
    }

    private byte[] downloadFile(MessageItem fileItem) throws Exception {
        return wechatClient.downloadFile(fileItem);
    }

    private String chatWithMemory(String fromUserId, String userMessage) {
        List<ChatMessage> history = memoryService.loadRecentMessages(fromUserId);
        String replyText = aiChatService.chat(userMessage, history);
        if (replyText != null && !replyText.trim().isEmpty()) {
            memoryService.appendConversation(fromUserId, userMessage, replyText);
        }
        return replyText;
    }

    private TypingIndicator startTypingIndicator(String fromUserId) {
        TypingIndicator indicator = new TypingIndicator(fromUserId);
        indicator.start();
        return indicator;
    }

    /**
     * 获取用户当前选择的 TTS 音色，没有配置时使用默认音色。
     */
    private String currentVoice(String fromUserId) {
        return userVoicePreferences.getOrDefault(fromUserId, VoiceCatalog.DEFAULT_VOICE);
    }

    private class TypingIndicator implements AutoCloseable {
        private final String fromUserId;
        private final AtomicBoolean running = new AtomicBoolean(true);
        private Thread keepAliveThread;

        private TypingIndicator(String fromUserId) {
            this.fromUserId = fromUserId;
        }

        private void start() {
            startTypingOnce();
            keepAliveThread = new Thread(() -> {
                while (running.get()) {
                    try {
                        Thread.sleep(4000L);
                        if (running.get()) {
                            startTypingOnce();
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }, "wechat-typing-indicator");
            keepAliveThread.setDaemon(true);
            keepAliveThread.start();
        }

        @Override
        public void close() {
            running.set(false);
            if (keepAliveThread != null) {
                keepAliveThread.interrupt();
            }
            try {
                wechatClient.stopTyping(fromUserId);
                System.out.println("⌨️ 已关闭正在输入状态");
            } catch (Exception e) {
                System.err.println("⚠️ 关闭正在输入状态失败: " + e.getMessage());
            }
        }

        private void startTypingOnce() {
            try {
                wechatClient.startTyping(fromUserId);
                System.out.println("⌨️ 已发送正在输入状态");
            } catch (Exception e) {
                System.err.println("⚠️ 开启正在输入状态失败: " + e.getMessage());
            }
        }
    }

    /**
     * 轻量级分段计时器，用于定位消息处理链路中的慢步骤。
     */
    private static class StepTimer {
        private final long startNanos = System.nanoTime();
        private long lastNanos = startNanos;

        private void mark(String stepName) {
            long now = System.nanoTime();
            long costMillis = (now - lastNanos) / 1_000_000L;
            if (costMillis >= SLOW_STEP_WARN_MS) {
                System.out.println("⏱️ 慢步骤: " + stepName + " cost=" + costMillis + "ms");
            }
            lastNanos = now;
        }

        private long totalMillis() {
            return (System.nanoTime() - startNanos) / 1_000_000L;
        }
    }
}
