package com.clawbot.wechatbot.feature.voice;

import com.clawbot.wechatbot.service.SpeechSynthesisService;
import com.clawbot.wechatbot.skills.SkillDefinition;
import com.clawbot.wechatbot.skills.SkillRequest;
import com.clawbot.wechatbot.skills.SkillResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class VoiceReplyFollowUpTests {

    @Test
    void reusesPreviousUserTextForMaleVoiceFollowUp() throws Exception {
        SpeechSynthesisService speech = mock(SpeechSynthesisService.class);
        VoiceReplyContextStore contexts = mock(VoiceReplyContextStore.class);
        when(contexts.find("user-1")).thenReturn(Optional.of("南京今天有小雨"));
        when(speech.synthesize("南京今天有小雨", "Ethan"))
            .thenReturn(new byte[] {1});
        when(speech.getFileExtension()).thenReturn("mp3");
        VoiceReplySkill skill = new VoiceReplySkill(speech, contexts);

        SkillResult result = skill.execute(definition(), new SkillRequest(
            "user-1", "男声回复", "", "", ""));

        assertTrue(result.success());
        assertTrue(result.attachments().getFirst().fileName().endsWith(".mp3"));
        verify(speech).synthesize("南京今天有小雨", "Ethan");
    }

    @Test
    void asksForContentWithoutRetryWhenNoPreviousTextExists() throws Exception {
        SpeechSynthesisService speech = mock(SpeechSynthesisService.class);
        VoiceReplyContextStore contexts = mock(VoiceReplyContextStore.class);
        when(contexts.find("user-1")).thenReturn(Optional.empty());
        VoiceReplySkill skill = new VoiceReplySkill(speech, contexts);

        SkillResult result = skill.execute(definition(), new SkillRequest(
            "user-1", "男声回复", "", "", ""));

        assertTrue(result.success());
        assertTrue(result.text().contains("请告诉我要朗读的内容"));
        verify(speech, never()).synthesize(anyString(), anyString());
    }

    private SkillDefinition definition() {
        return new SkillDefinition(
            "voice-reply", "1.0.0", true, "语音回复",
            "生成语音", "voice-reply",
            List.of(), List.of(), 60, false);
    }
}
