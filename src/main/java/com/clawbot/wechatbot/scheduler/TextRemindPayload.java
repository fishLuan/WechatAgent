package com.clawbot.wechatbot.scheduler;

/** 最基础的「文字提醒」Payload —— 就是现在老版本「提醒我喝水」这种功能。
 *  之前硬编码在 AgentTaskScheduler.buildRunnable 里的逻辑，现在搬到这里。*/
public class TextRemindPayload implements TaskPayload {

    public static final String TYPE = "TEXT_REMIND";

    private final String message;

    public TextRemindPayload(String message) {
        this.message = message == null ? "" : message.trim();
    }

    public String getMessage() { return message; }

    @Override public String getType() { return TYPE; }

    @Override public String getDisplayName() {
        if (message.isEmpty()) return "文字提醒";
        return message.length() <= 15 ? message : message.substring(0, 15) + "…";
    }

    @Override
    public void execute(WeChatMessageSender sender, String userId) throws Exception {
        sender.sendText(userId, "⏰ 提醒：" + message);
    }

    @Override public String toJson() {
        String escaped = message == null ? "" : message
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t");
        return "{\"type\":\"" + TYPE + "\",\"message\":\"" + escaped + "\"}";
    }
}