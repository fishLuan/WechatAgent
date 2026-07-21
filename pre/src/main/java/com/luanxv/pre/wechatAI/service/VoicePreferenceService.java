package com.luanxv.pre.wechatAI.service;

import com.luanxv.pre.wechatAI.config.BotConfig;
import com.luanxv.pre.wechatAI.model.UserVoicePreference;
import com.luanxv.pre.wechatAI.model.VoiceProfile;
import com.luanxv.pre.wechatAI.repository.UserVoicePreferenceRepository;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Optional;

/** Parses voice preferences and persists only explicit long-term user choices. */
@Service
public class VoicePreferenceService {
    private final UserVoicePreferenceRepository repository;
    private final BotConfig config;

    public VoicePreferenceService(UserVoicePreferenceRepository repository, BotConfig config) {
        this.repository = repository;
        this.config = config;
    }

    public VoiceCommand parse(String text) {
        String value = text == null ? "" : text.trim();
        boolean male = containsAny(value, "\u7537\u58f0", "\u7537\u751f", "\u6210\u5e74\u7537\u4eba", "\u6210\u5e74\u7537\u6027",
                "\u7537\u6027\u97f3\u8272", "\u7537\u6027\u58f0\u97f3", "\u7537\u7684\u58f0\u97f3", "\u7537\u97f3");
        boolean female = containsAny(value, "\u5973\u58f0", "\u5973\u751f", "\u6210\u5e74\u5973\u4eba", "\u6210\u5e74\u5973\u6027",
                "\u5973\u6027\u97f3\u8272", "\u5973\u6027\u58f0\u97f3", "\u5973\u7684\u58f0\u97f3", "\u5973\u97f3");
        VoiceProfile profile = male ? VoiceProfile.MALE : female ? VoiceProfile.FEMALE : VoiceProfile.DEFAULT;
        boolean clear = value.contains("\u6062\u590d\u9ed8\u8ba4") || value.contains("\u53d6\u6d88\u97f3\u8272")
                || value.contains("\u6e05\u9664\u97f3\u8272\u504f\u597d");
        boolean persist = !clear && (value.contains("\u4ee5\u540e") || value.contains("\u4eca\u540e")
                || value.contains("\u9ed8\u8ba4") || value.contains("\u4e00\u76f4") || value.contains("\u90fd\u7528"));
        // Specifying a gender is itself an explicit request for an audio reply.
        boolean asksForVoice = male || female || value.contains("\u8bed\u97f3\u56de\u590d") || value.contains("\u8bed\u97f3\u56de\u7b54");
        String question = value.replaceAll("(\u8bf7|\u7528|\u7537\u58f0|\u5973\u58f0|\u7537\u751f|\u5973\u751f|\u6210\u5e74\u7537\u4eba|\u6210\u5e74\u5973\u4eba|\u6210\u5e74\u7537\u6027|\u6210\u5e74\u5973\u6027|\u7537\u6027\u97f3\u8272|\u5973\u6027\u97f3\u8272|\u7537\u6027\u58f0\u97f3|\u5973\u6027\u58f0\u97f3|\u7537\u7684\u58f0\u97f3|\u5973\u7684\u58f0\u97f3|\u7537\u97f3|\u5973\u97f3|"
                + "\u8bed\u97f3\u56de\u590d\u6211?|\u8bed\u97f3\u56de\u7b54\u6211?|\u56de\u590d\u6211?|\u56de\u7b54\u6211?|"
                + "\u4ee5\u540e|\u4eca\u540e|\u9ed8\u8ba4|\u4e00\u76f4|\u90fd\u7528)", "")
                .replaceAll("^[，,。：:\\s]+|[，,。：:\\s]+$", "").trim();
        return new VoiceCommand(profile, asksForVoice, persist, clear, question);
    }

    public void savePreference(String userId, VoiceProfile profile) {
        if (profile == VoiceProfile.DEFAULT) {
            return;
        }
        try {
            repository.save(new UserVoicePreference(userId, profile, Instant.now()));
        } catch (RuntimeException exception) {
            System.err.println("保存语音偏好失败: " + exception.getMessage());
        }
    }

    public void clearPreference(String userId) {
        try {
            repository.deleteById(userId);
        } catch (RuntimeException exception) {
            System.err.println("清除语音偏好失败: " + exception.getMessage());
        }
    }

    public String resolveVoiceId(String userId, VoiceProfile requestedProfile) {
        VoiceProfile profile = requestedProfile == VoiceProfile.DEFAULT ? loadPreference(userId) : requestedProfile;
        return profile == VoiceProfile.MALE ? config.getSpeechMaleVoice() : config.getSpeechVoice();
    }

    public String resolveModelId(String userId, VoiceProfile requestedProfile) {
        VoiceProfile profile = requestedProfile == VoiceProfile.DEFAULT ? loadPreference(userId) : requestedProfile;
        return profile == VoiceProfile.MALE ? config.getSpeechMaleModel() : config.getSpeechSynthesisModel();
    }

    private boolean containsAny(String value, String... candidates) {
        for (String candidate : candidates) {
            if (value.contains(candidate)) {
                return true;
            }
        }
        return false;
    }

    private VoiceProfile loadPreference(String userId) {
        try {
            Optional<UserVoicePreference> preference = repository.findById(userId);
            return preference.map(UserVoicePreference::getVoiceProfile).orElse(VoiceProfile.DEFAULT);
        } catch (RuntimeException exception) {
            System.err.println("读取语音偏好失败: " + exception.getMessage());
            return VoiceProfile.DEFAULT;
        }
    }

    public record VoiceCommand(VoiceProfile profile, boolean asksForVoice, boolean persistPreference,
                               boolean clearPreference, String question) {
        public boolean isPreferenceOnly() {
            return question == null || question.isBlank();
        }
    }
}
