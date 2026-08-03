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
        VoiceReplySkill skill = new VoiceReplySkill(speech);

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
}
