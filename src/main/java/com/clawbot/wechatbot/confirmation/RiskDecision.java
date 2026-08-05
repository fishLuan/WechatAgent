package com.clawbot.wechatbot.confirmation;

public record RiskDecision(boolean confirmationRequired, String level, String summary) {
    public static RiskDecision safe() { return new RiskDecision(false, "LOW", ""); }
}
