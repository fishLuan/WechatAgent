package com.clawbot.wechatbot.feature.document.model;

public record WordDocumentEditResult(
    boolean success,
    String message,
    WordDocumentSession session,
    boolean shouldSendFile
) {
    public static WordDocumentEditResult success(String message, WordDocumentSession session) {
        return new WordDocumentEditResult(true, message, session, false);
    }

    public static WordDocumentEditResult success(
        String message, WordDocumentSession session, boolean shouldSendFile
    ) {
        return new WordDocumentEditResult(true, message, session, shouldSendFile);
    }

    public static WordDocumentEditResult failure(String message) {
        return new WordDocumentEditResult(false, message, null, false);
    }
}
