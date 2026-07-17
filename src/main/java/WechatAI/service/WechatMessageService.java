package WechatAI.service;

import com.github.wechat.ilink.sdk.ILinkClient;
import com.github.wechat.ilink.sdk.core.model.MessageItem;
import com.github.wechat.ilink.sdk.core.model.WeixinMessage;

import java.util.List;

public class WechatMessageService {

    private static final String FALLBACK_REPLY = "抱歉，我现在有点忙，稍后再回复你哦~";
    private static final String IMAGE_FALLBACK_REPLY = "抱歉，我现在还没能处理这张图片，请稍后再试。";
    private static final String IMAGE_GENERATION_FALLBACK_REPLY = "抱歉，图片生成失败了。请确认 qwen-image 已在阿里云百炼开通额度，并且 config.properties 里已填写 qwen.api.key。";
    private static final long TYPING_DELAY_MS = 1500L;

    private final ILinkClient client;
    private final AiChatService aiChatService;
    private final ImageUnderstandingService imageUnderstandingService;
    private final ImageGenerationService imageGenerationService;
    private final MessageTextExtractor textExtractor;

    public WechatMessageService(
            ILinkClient client,
            AiChatService aiChatService,
            ImageUnderstandingService imageUnderstandingService,
            ImageGenerationService imageGenerationService,
            MessageTextExtractor textExtractor
    ) {
        this.client = client;
        this.aiChatService = aiChatService;
        this.imageUnderstandingService = imageUnderstandingService;
        this.imageGenerationService = imageGenerationService;
        this.textExtractor = textExtractor;
    }

    public void handleAndReply(WeixinMessage message) {
        try {
            String receivedText = textExtractor.extract(message);
            String fromUserId = message.getFrom_user_id();

            System.out.println("💬 收到来自 " + fromUserId + " 的消息: " + receivedText);
            if (handleImageMessage(message, receivedText, fromUserId)) {
                return;
            }

            if (receivedText == null || receivedText.trim().isEmpty()) {
                System.out.println("⏭️ 消息为空，跳过回复");
                return;
            }

            if (isImageGenerationRequest(receivedText)) {
                handleImageGeneration(fromUserId, receivedText);
                return;
            }

            String replyText = aiChatService.chat(receivedText);
            if (replyText == null || replyText.isEmpty()) {
                replyText = FALLBACK_REPLY;
            }

            client.sendTextWithTyping(fromUserId, replyText, TYPING_DELAY_MS);
            System.out.println("✅ 回复已发送: " + replyText);
        } catch (Exception e) {
            System.err.println("❌ 处理消息失败: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private boolean handleImageMessage(WeixinMessage message, String prompt, String fromUserId) throws Exception {
        MessageItem imageItem = firstImageItem(message);
        if (imageItem == null) {
            return false;
        }

        System.out.println("🖼️ 收到图片消息，开始下载并理解图片");
        byte[] imageBytes = client.downloadImageFromMessageItem(imageItem);
        String replyText = imageUnderstandingService.understand(imageBytes, prompt);
        if (replyText == null || replyText.isEmpty()) {
            replyText = IMAGE_FALLBACK_REPLY;
        }

        client.sendTextWithTyping(fromUserId, replyText, TYPING_DELAY_MS);
        System.out.println("✅ 图片理解回复已发送: " + replyText);
        return true;
    }

    private void handleImageGeneration(String fromUserId, String receivedText) throws Exception {
        String prompt = normalizeImagePrompt(receivedText);
        System.out.println("🎨 收到图片生成请求: " + prompt);
        client.startTyping(fromUserId);
        byte[] imageBytes = imageGenerationService.generate(prompt);
        client.stopTyping(fromUserId);

        if (imageBytes == null || imageBytes.length == 0) {
            String errorMessage = imageGenerationService.getLastErrorMessage();
            String replyText = errorMessage == null || errorMessage.isEmpty()
                    ? IMAGE_GENERATION_FALLBACK_REPLY
                    : "抱歉，图片生成失败了：" + errorMessage;
            client.sendTextWithTyping(fromUserId, replyText, TYPING_DELAY_MS);
            return;
        }

        client.sendImage(fromUserId, imageBytes, "generated-image.png", "image/png");
        System.out.println("✅ 生成图片已发送");
    }

    private MessageItem firstImageItem(WeixinMessage message) {
        List<MessageItem> items = message.getItem_list();
        if (items == null || items.isEmpty()) {
            return null;
        }
        for (MessageItem item : items) {
            if (item.getImage_item() != null) {
                return item;
            }
        }
        return null;
    }

    private boolean isImageGenerationRequest(String text) {
        String normalized = text == null ? "" : text.trim();
        if (normalized.startsWith("画图")
                || normalized.startsWith("绘图")
                || normalized.startsWith("生成图片")
                || normalized.startsWith("生成一张图")
                || normalized.startsWith("帮我画")) {
            return true;
        }

        boolean asksToCreate = normalized.contains("生成")
                || normalized.contains("画")
                || normalized.contains("绘制")
                || normalized.contains("做一张")
                || normalized.contains("来一张");
        boolean targetIsImage = normalized.contains("图")
                || normalized.contains("图片")
                || normalized.contains("照片")
                || normalized.contains("海报")
                || normalized.contains("头像")
                || normalized.contains("壁纸");
        return asksToCreate && targetIsImage;
    }

    private String normalizeImagePrompt(String text) {
        String prompt = text.replaceFirst("^(画图|绘图|生成图片|生成一张图|帮我画)[:：,，\\s]*", "").trim();
        if (prompt.isEmpty()) {
            return text;
        }
        return prompt;
    }
}
