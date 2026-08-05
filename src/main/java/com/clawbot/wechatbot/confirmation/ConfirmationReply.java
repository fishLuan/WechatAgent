package com.clawbot.wechatbot.confirmation;

public record ConfirmationReply(boolean handled, boolean continueWithAgent,
                                String message, String revisedRequest) {
    public static ConfirmationReply notHandled() { return new ConfirmationReply(false, false, "", ""); }
}
