package com.clawbot.wechatbot.memory;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "clawbot.memory")
public class MemoryProperties {
    private boolean enabled = true;
    private String namespace = "default";
    private int recentTurns = 15;
    private int summaryEvery = 10;
    private long messageDedupTtlMinutes = 30;

    @PostConstruct
    void validate() {
        if (namespace == null || namespace.isBlank()) {
            throw new IllegalStateException("clawbot.memory.namespace must not be blank");
        }
        if (recentTurns < 1) {
            throw new IllegalStateException("clawbot.memory.recent-turns must be greater than 0");
        }
        if (summaryEvery < 1) {
            throw new IllegalStateException("clawbot.memory.summary-every must be greater than 0");
        }
        if (messageDedupTtlMinutes < 1) {
            throw new IllegalStateException(
                "clawbot.memory.message-dedup-ttl-minutes must be greater than 0");
        }
    }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getNamespace() { return namespace; }
    public void setNamespace(String namespace) { this.namespace = namespace; }
    public int getRecentTurns() { return recentTurns; }
    public void setRecentTurns(int recentTurns) { this.recentTurns = recentTurns; }
    public int getSummaryEvery() { return summaryEvery; }
    public void setSummaryEvery(int summaryEvery) { this.summaryEvery = summaryEvery; }
    public long getMessageDedupTtlMinutes() { return messageDedupTtlMinutes; }
    public void setMessageDedupTtlMinutes(long value) { this.messageDedupTtlMinutes = value; }
}
