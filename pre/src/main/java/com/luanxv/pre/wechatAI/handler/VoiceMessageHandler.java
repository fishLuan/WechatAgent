package com.luanxv.pre.wechatAI.handler;

import com.github.wechat.ilink.sdk.ILinkClient;
import com.github.wechat.ilink.sdk.core.model.MessageItem;
import com.luanxv.pre.wechatAI.model.MessageContext;
import com.luanxv.pre.wechatAI.service.QwenService;
import com.luanxv.pre.wechatAI.service.VoicePreferenceService;

import java.util.Base64;

/** Transcribes voice messages, then optionally answers with a selected TTS voice. */
public class VoiceMessageHandler implements MessageTypeHandler {
    private final ILinkClient client;
    private final QwenService qwenService;
    private final VoicePreferenceService voicePreferenceService;

    public VoiceMessageHandler(ILinkClient client, QwenService qwenService,
                               VoicePreferenceService voicePreferenceService) {
        this.client = client;
        this.qwenService = qwenService;
        this.voicePreferenceService = voicePreferenceService;
    }

    @Override
    public String handle(MessageContext context) {
        try {
            String transcript = transcribe(context.getVoiceItem());
            if (transcript == null || transcript.isBlank()) {
                return "没有识别出语音内容，请再说一次。";
            }
            String userId = context.getFromUserId();
            VoicePreferenceService.VoiceCommand command = voicePreferenceService.parse(transcript);
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

            String question = command.question().isBlank() ? transcript : command.question();
            String reply = qwenService.chat(userId, question);
            if (reply == null || reply.isBlank()) {
                return "暂时无法回答，请稍后再试。";
            }
            if (!command.asksForVoice()) {
                return "识别：" + transcript + "\n回答：" + reply;
            }
            byte[] speech = qwenService.synthesizeSpeech(reply,
                    voicePreferenceService.resolveVoiceId(userId, command.profile()),
                    voicePreferenceService.resolveModelId(userId, command.profile()));
            return speech == null || speech.length == 0 ? reply : "MP3:" + Base64.getEncoder().encodeToString(speech);
        } catch (Exception exception) {
            System.err.println("处理语音消息失败: " + exception.getMessage());
            return "处理语音时出现问题，请稍后再试。";
        }
    }

    private String transcribe(MessageItem voiceItem) throws Exception {
        String transcript = voiceItem.getVoice_item().getText();
        if (transcript == null || transcript.isBlank()) {
            transcript = qwenService.recognizeSpeech(client.downloadVoiceFromMessageItem(voiceItem));
        }
        return transcript == null ? null : transcript.trim();
    }

    @Override
    public boolean supports(MessageContext context) {
        return context.isHasVoice();
    }
}
