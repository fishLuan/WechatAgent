package com.clawbot.wechatbot.feature.voice;

import com.clawbot.wechatbot.service.SpeechSynthesisService;
import com.clawbot.wechatbot.skills.SkillDefinition;
import com.clawbot.wechatbot.skills.SkillRequest;
import com.clawbot.wechatbot.skills.SkillResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class VoiceReplySkillTests {
    @Test
    void synthesizesDependencyTextWithRequestedMaleVoice() throws Exception {
        SpeechSynthesisService speech = mock(SpeechSynthesisService.class);
        when(speech.synthesize("天气晴朗", "Ethan"))
            .thenReturn(new byte[] {1, 2});
        when(speech.getFileExtension()).thenReturn("mp3");
        VoiceReplyContextStore contexts = mock(VoiceReplyContextStore.class);
        VoiceReplySkill skill = new VoiceReplySkill(speech, contexts);

        SkillResult result = skill.execute(
            new SkillDefinition(
                "voice-reply", "1.0.0", true, "语音回复",
                "生成语音", "voice-reply",
                List.of(), List.of(), 60, false),
            new SkillRequest(
                "user", "使用男声回复", "", "", "天气晴朗"));

        assertTrue(result.success());
        assertTrue(result.attachments().getFirst().fileName().endsWith(".mp3"));
        verify(speech).synthesize("天气晴朗", "Ethan");
    }

    @Test
    void speaksStructuredDependencyValueInsteadOfJson() throws Exception {
        SpeechSynthesisService speech = mock(SpeechSynthesisService.class);
        when(speech.synthesize("北京今天晴，气温32℃。", "Cherry"))
            .thenReturn(new byte[] {1});
        when(speech.getFileExtension()).thenReturn("mp3");
        VoiceReplySkill skill = new VoiceReplySkill(
            speech, mock(VoiceReplyContextStore.class));

        skill.execute(null, new SkillRequest(
            "user", "用女声回复", "", "",
            "【查询北京天气】\n{\"display_text\":\"北京今天晴，气温32℃。\"}"));

        verify(speech).synthesize("北京今天晴，气温32℃。", "Cherry");
    }
}
