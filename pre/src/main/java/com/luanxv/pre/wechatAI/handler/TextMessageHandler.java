package com.luanxv.pre.wechatAI.handler;

import com.github.wechat.ilink.sdk.ILinkClient;
import com.luanxv.pre.wechatAI.core.ConversationMemoryService;
import com.luanxv.pre.wechatAI.core.GeneratedImageStore;
import com.luanxv.pre.wechatAI.model.AgentIntent;
import com.luanxv.pre.wechatAI.model.AgentTool;
import com.luanxv.pre.wechatAI.model.MessageContext;
import com.luanxv.pre.wechatAI.service.AgentIntentService;
import com.luanxv.pre.wechatAI.service.QwenService;
import com.luanxv.pre.wechatAI.service.VoicePreferenceService;

import java.io.IOException;
import java.util.Base64;
import java.util.Optional;

public class TextMessageHandler implements MessageTypeHandler {
    private final QwenService qwenService;
    private final VoicePreferenceService voicePreferenceService;
    private final AgentIntentService agentIntentService;
    private final ILinkClient client;
    private final GeneratedImageStore imageStore;
    private final ConversationMemoryService memoryService;

    public TextMessageHandler(ILinkClient client, QwenService qwenService, VoicePreferenceService voicePreferenceService,
                              AgentIntentService agentIntentService, GeneratedImageStore imageStore,
                              ConversationMemoryService memoryService) {
        this.client = client;
        this.qwenService = qwenService;
        this.voicePreferenceService = voicePreferenceService;
        this.agentIntentService = agentIntentService;
        this.imageStore = imageStore;
        this.memoryService = memoryService;
    }

    @Override
    public String handle(MessageContext context) {
        String userId = context.getFromUserId();
        VoicePreferenceService.VoiceCommand command = voicePreferenceService.parse(context.getTextContent());
        if (command.clearPreference()) {
            voicePreferenceService.clearPreference(userId);
            return "已恢复默认音色。";
        }
        if (command.persistPreference()) {
            voicePreferenceService.savePreference(userId, command.profile());
            if (command.isPreferenceOnly()) {
                return command.profile().name().equals("MALE") ? "已将默认语音设为男声。" : "已将默认语音设为女声。";
            }
        }

        String question = command.question().isBlank() ? context.getTextContent() : command.question();
        AgentIntent intent = agentIntentService.route(question);
        String reply = executeIntent(userId, intent);
        if (reply == null || reply.isBlank()) {
            return "抱歉，我暂时无法回答，请稍后再试。";
        }
        if (!command.asksForVoice()) {
            return reply;
        }
            byte[] speech = qwenService.synthesizeSpeech(reply,
                    voicePreferenceService.resolveVoiceId(userId, command.profile()),
                    voicePreferenceService.resolveModelId(userId, command.profile()));
        return speech == null || speech.length == 0 ? reply : "MP3:" + Base64.getEncoder().encodeToString(speech);
    }

    private String executeIntent(String userId, AgentIntent intent) {
        return switch (intent.tool()) {
            case CHAT -> qwenService.chat(userId, intent.argument());
            case WEB_SEARCH -> qwenService.chat(userId, intent.argument(), true);
            case IMAGE_GENERATE -> generateImage(userId, intent.argument());
            case IMAGE_EDIT -> editImage(userId, intent.argument());
        };
    }

    private String generateImage(String userId, String prompt) {
        sendProgress(userId, "图片生成中，请等待…");
        byte[] image = qwenService.generateImage(prompt);
        if (image == null || image.length == 0) {
            return "图片生成失败，请稍后再试。";
        }
        try {
            imageStore.save(userId, image);
        } catch (IOException exception) {
            System.err.println("保存生成图片失败: " + exception.getMessage());
        }
        memoryService.addMessage(userId, "user", "[图片生成请求] " + prompt);
        memoryService.addMessage(userId, "assistant", "[已生成图片]");
        return "IMAGE:" + Base64.getEncoder().encodeToString(image);
    }

    private String editImage(String userId, String instruction) {
        try {
            Optional<GeneratedImageStore.StoredImage> image = imageStore.load(userId);
            if (image.isEmpty()) {
                return "我还没有找到可修改的图片，请先发送图片或让我生成一张图片。";
            }
            sendProgress(userId, "图片修改中，请等待…");
            byte[] edited = qwenService.editImage(image.get().bytes(), instruction);
            if (edited == null || edited.length == 0) {
                return "图片修改失败，请稍后再试。";
            }
            imageStore.save(userId, edited);
            memoryService.addMessage(userId, "user", "[图片修改请求] " + instruction);
            memoryService.addMessage(userId, "assistant", "[已修改图片]");
            return "IMAGE:" + Base64.getEncoder().encodeToString(edited);
        } catch (IOException exception) {
            System.err.println("读取图片缓存失败: " + exception.getMessage());
            return "图片缓存读取失败，请重新发送图片后再试。";
        }
    }

    private void sendProgress(String userId, String message) {
        try {
            client.sendText(userId, message);
        } catch (IOException exception) {
            System.err.println("发送 Agent 工具状态失败: " + exception.getMessage());
        }
    }

    @Override
    public boolean supports(MessageContext context) {
        return !context.isHasImage() && context.getTextContent() != null && !context.getTextContent().isBlank();
    }
}
