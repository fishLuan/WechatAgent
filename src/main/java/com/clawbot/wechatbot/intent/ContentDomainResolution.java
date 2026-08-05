package com.clawbot.wechatbot.intent;

public record ContentDomainResolution(
    Domain domain, double confidence, String evidence
) {
    public enum Domain { BOOK, BILIBILI, BOTH, UNKNOWN }
}
