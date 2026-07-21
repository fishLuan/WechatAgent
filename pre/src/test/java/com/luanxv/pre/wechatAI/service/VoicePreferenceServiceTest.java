package com.luanxv.pre.wechatAI.service;

import com.luanxv.pre.wechatAI.config.BotConfig;
import com.luanxv.pre.wechatAI.model.UserVoicePreference;
import com.luanxv.pre.wechatAI.model.VoiceProfile;
import com.luanxv.pre.wechatAI.repository.UserVoicePreferenceRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class VoicePreferenceServiceTest {
    @Test
    void recognizesAOneTimeMaleVoiceRequest() {
        VoicePreferenceService service = serviceWithDefaults(mock(UserVoicePreferenceRepository.class));

        VoicePreferenceService.VoiceCommand command = service.parse(
                "\u8bf7\u7528\u7537\u58f0\u56de\u590d\u6211\uff0c\u4eca\u5929\u676d\u5dde\u5929\u6c14\u600e\u4e48\u6837");

        assertEquals(VoiceProfile.MALE, command.profile());
        assertTrue(command.asksForVoice());
        assertTrue(command.question().contains("\u676d\u5dde"));
    }

    @Test
    void recognizesAdultMaleVoiceWordingFromSpeechRecognition() {
        VoicePreferenceService service = serviceWithDefaults(mock(UserVoicePreferenceRepository.class));

        VoicePreferenceService.VoiceCommand command = service.parse(
                "\u8bf7\u7528\u6210\u5e74\u7537\u4eba\u7684\u97f3\u8272\u8bed\u97f3\u56de\u590d\u6211\u5317\u4eac\u5929\u6c14");

        assertEquals(VoiceProfile.MALE, command.profile());
        assertTrue(command.asksForVoice());
        assertTrue(command.question().contains("\u5317\u4eac\u5929\u6c14"));
    }

    @Test
    void treatsFemaleVoiceSelectionAsAnAudioReplyRequest() {
        VoicePreferenceService service = serviceWithDefaults(mock(UserVoicePreferenceRepository.class));

        VoicePreferenceService.VoiceCommand command = service.parse("\u8bf7\u7528\u5973\u58f0\u8bf4\u4eca\u5929\u5317\u4eac\u5929\u6c14");

        assertEquals(VoiceProfile.FEMALE, command.profile());
        assertTrue(command.asksForVoice());
    }

    @Test
    void resolvesPersistedMaleVoiceForLaterVoiceReplies() {
        UserVoicePreferenceRepository repository = mock(UserVoicePreferenceRepository.class);
        when(repository.findById("user-1")).thenReturn(Optional.of(
                new UserVoicePreference("user-1", VoiceProfile.MALE, Instant.now())));
        VoicePreferenceService service = serviceWithDefaults(repository);

        assertEquals("male-voice", service.resolveVoiceId("user-1", VoiceProfile.DEFAULT));
    }

    private VoicePreferenceService serviceWithDefaults(UserVoicePreferenceRepository repository) {
        BotConfig config = new BotConfig();
        config.setSpeechVoice("female-voice");
        config.setSpeechMaleVoice("male-voice");
        return new VoicePreferenceService(repository, config);
    }
}
