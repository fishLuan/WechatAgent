package com.clawbot.wechatbot.scheduler;

import java.util.Map;
import com.fasterxml.jackson.databind.ObjectMapper;

public final class TaskPayloadFactory {

    private static final ObjectMapper OM = new ObjectMapper();

    private TaskPayloadFactory() {}

    /** 100% 兼容老数据 + 新数据的统一入口：
     *  - 新数据（payloadType=TEXT_REMIND）：反序列化 payloadJson
     *  - 老数据（payloadType=null/空）：直接用旧的 ScheduledTask.message 字段造一个 TextRemindPayload
     *  - 以后加 B 站/其他类型：这里加一个 case 即可，调度器不用改 */
    public static TaskPayload from(ScheduledTask task) {
        if (task == null) return new TextRemindPayload("");
        String type = task.payloadType();
        String json = task.payloadJson();

        if ((type == null || type.isBlank()) && (json == null || json.isBlank())) {
            return new TextRemindPayload(task.message() == null ? task.name() : task.message());
        }

        String useType = (type == null || type.isBlank()) ? TextRemindPayload.TYPE : type;

        try {
            if (TextRemindPayload.TYPE.equals(useType)) {
                return fromTextRemind(json, task);
            }
            return new TextRemindPayload(
                (task.message() == null || task.message().isBlank())
                    ? ("未知任务类型：" + useType)
                    : task.message()
            );
        } catch (Exception e) {
            return new TextRemindPayload(
                (task.message() == null || task.message().isBlank())
                    ? ("任务内容解析失败：" + e.getMessage())
                    : task.message()
            );
        }
    }

    private static TextRemindPayload fromTextRemind(String json, ScheduledTask task) throws Exception {
        if (json == null || json.isBlank()) {
            return new TextRemindPayload(task.message() == null ? "" : task.message());
        }
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> m = OM.readValue(json, Map.class);
            Object msg = m.get("message");
            return new TextRemindPayload(msg == null ? "" : String.valueOf(msg));
        } catch (Exception e) {
            return new TextRemindPayload(task.message() == null ? "" : task.message());
        }
    }
}