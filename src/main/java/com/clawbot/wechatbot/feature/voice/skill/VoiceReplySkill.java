package com.clawbot.wechatbot.feature.voice.skill;

import com.clawbot.wechatbot.service.SpeechSynthesisService;
import com.clawbot.wechatbot.service.agent.AgentAttachment;
import com.clawbot.wechatbot.skills.SkillDefinition;
import com.clawbot.wechatbot.skills.SkillExecutor;
import com.clawbot.wechatbot.skills.SkillRequest;
import com.clawbot.wechatbot.skills.SkillResult;
import org.springframework.stereotype.Component;

import java.util.List;

/** Converts prepared text into an audio file attachment. */
@Component
public final class VoiceReplySkill implements SkillExecutor {
    public static final String EXECUTOR_NAME = "voice-reply";
    private final SpeechSynthesisService speech;

    public VoiceReplySkill(SpeechSynthesisService speech) {
        this.speech = speech;
    }

    @Override
    public String executorName() {
        return EXECUTOR_NAME;
    }

    @Override
    public SkillResult execute(SkillDefinition definition, SkillRequest request)
        throws Exception {
        if (request == null) {
            return SkillResult.failure("Voice reply request cannot be empty");
        }
        String text = resolveText(request);
        if (text.isBlank()) {
            return SkillResult.failure(
                "No text is available for speech synthesis; generate or provide it first");
        }
        String voice = selectVoice(request.instruction());
        byte[] bytes = speech.synthesize(text, voice);
        String extension = speech.getFileExtension();
        AgentAttachment attachment = new AgentAttachment(
            AgentAttachment.AttachmentType.FILE,
            bytes,
            "voice-" + System.currentTimeMillis() + "." + extension,
            "语音回复（音色：" + voice + "）");
        return SkillResult.success(
            "语音文件已生成：" + attachment.fileName(), List.of(attachment));
    }

    private String resolveText(SkillRequest request) {
        if (!request.dependencyText().isBlank()) return request.dependencyText();
        String instruction = request.instruction();
        for (String separator : List.of("：", ":")) {
            int index = instruction.indexOf(separator);
            if (index >= 0 && index + separator.length() < instruction.length()) {
                return instruction.substring(index + separator.length()).trim();
            }
        }
        return "";
    }

    private String selectVoice(String instruction) {
        if (instruction.contains("男声")) return "Ethan";
        if (instruction.contains("女声")) return "Cherry";
        return "Cherry";
    }
}
