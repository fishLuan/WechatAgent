package com.clawbot.wechatbot.feature.voice;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.util.Date;

@Document(collection = "voice_reply_context")
public class VoiceReplyContext {
    @Id private String userId;
    private String text;
    private long updatedAt;
    @Indexed(expireAfter = "0s") private Date expiresAt;

    public String getUserId() { return userId; }
    public void setUserId(String value) { userId = value; }
    public String getText() { return text; }
    public void setText(String value) { text = value; }
    public long getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(long value) { updatedAt = value; }
    public Date getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Date value) { expiresAt = value; }
}
