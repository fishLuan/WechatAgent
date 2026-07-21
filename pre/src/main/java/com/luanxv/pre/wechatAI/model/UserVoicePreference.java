package com.luanxv.pre.wechatAI.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

@Document("user_voice_preferences")
public class UserVoicePreference {
    @Id
    private String userId;
    private VoiceProfile voiceProfile;
    private Instant updatedAt;

    public UserVoicePreference() {
    }

    public UserVoicePreference(String userId, VoiceProfile voiceProfile, Instant updatedAt) {
        this.userId = userId;
        this.voiceProfile = voiceProfile;
        this.updatedAt = updatedAt;
    }

    public String getUserId() { return userId; }
    public VoiceProfile getVoiceProfile() { return voiceProfile; }
    public Instant getUpdatedAt() { return updatedAt; }
}
