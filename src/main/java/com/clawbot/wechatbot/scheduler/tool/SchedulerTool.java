package com.clawbot.wechatbot.scheduler.tool;

import com.clawbot.wechatbot.idempotency.IdempotencyClaim;
import com.clawbot.wechatbot.idempotency.IdempotencyExecution;
import com.clawbot.wechatbot.idempotency.IdempotencyService;
import com.clawbot.wechatbot.idempotency.IdempotencyStatus;
import com.clawbot.wechatbot.scheduler.controller.SchedulerControlService;
import com.clawbot.wechatbot.scheduler.model.ScheduledSubscription;
import com.clawbot.wechatbot.scheduler.model.TaskType;
import com.clawbot.wechatbot.service.agent.AgentRequestContextHolder;
import com.clawbot.wechatbot.tools.FunctionTool;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class SchedulerTool implements FunctionTool {

    public static final String TOOL_NAME = "scheduler_manage";
    private static final String SUB_CREATE = "create_subscription";
    private static final String SUB_CANCEL = "cancel_subscription";
    private static final String SUB_LIST = "list_subscriptions";
    private final SchedulerControlService controlService;
    private final ObjectMapper mapper;
    private final AgentRequestContextHolder requestContextHolder;
    private final IdempotencyService idempotencyService;

    public SchedulerTool(
        @Lazy SchedulerControlService controlService,
        ObjectMapper mapper,
        AgentRequestContextHolder requestContextHolder,
        IdempotencyService idempotencyService
    ) {
        this.controlService = controlService;
        this.mapper = mapper;
        this.requestContextHolder = requestContextHolder;
        this.idempotencyService = idempotencyService;
    }

    @Override
    public String name() { return TOOL_NAME; }

    @Override
    public JsonNode definition() {
        ObjectNode root = mapper.createObjectNode();
        root.put("type", "function");
        ObjectNode func = root.putObject("function");
        func.put("name", TOOL_NAME);
        func.put("description",
                "管理当前对话用户的微信消息定时订阅任务。不用传 user_id，系统自动识别当前对话用户。" +
                "【重要】只要用户提到具体时间（如『11点』『9点29分』『每天』『明天早上』『几点几分』）并要求推送/提醒/订阅内容，就是在创建定时任务，必须调用本工具创建，绝不能立即执行或立即推送内容。" +
                "取消订阅支持三种方式：① 用户说『取消所有』『全部取消』→ cancel_all=true；② 用户指定『取消订阅编号xxx』→ 填 subscription_id；③ 用户说指定任务类型→ 填 task_type。" +
                "创建 B 站推荐推送任务：当用户说『几点推送几个电影/动漫/剧集』时，task_type=BILIBILI_PUSH，params_json 里写 {\"content_type\":\"MOVIE或BANGUMI或SERIES\",\"count\":数量}，时间用 time_daily_hhmm（每天重复）或 is_one_time+one_time_datetime（单次）。");

        ObjectNode params = func.putObject("parameters");
        params.put("type", "object");
        ObjectNode props = params.putObject("properties");

        ObjectNode action = props.putObject("action");
        action.put("type", "string");
        ArrayNode enums = action.putArray("enum");
        enums.add(SUB_CREATE).add(SUB_CANCEL).add(SUB_LIST);
        action.put("description", "create=创建订阅 cancel=取消 list=列出所有订阅");

        ObjectNode subIdNode = props.putObject("subscription_id");
        subIdNode.put("type", "string");
        subIdNode.put("description", "[取消时可选] 订阅编号ID，用户说『取消订阅编号xxx』或指定某一个时填，精确不会误删");

        ObjectNode cancelAllNode = props.putObject("cancel_all");
        cancelAllNode.put("type", "boolean");
        cancelAllNode.put("description", "[取消时必选首选] 用户说以下任何一句话就立刻填 true：『取消所有订阅』『全部取消』『所有订阅都取消』『不想再收到任何定时了』『删掉所有定时』『全取消』『取消全部』——只要用户表达了全部取消的意思，这字段必须是 true，别用其他方式取消！");

        ObjectNode cancelMatchingAllNode = props.putObject("cancel_matching_all");
        cancelMatchingAllNode.put("type", "boolean");
        cancelMatchingAllNode.put("description",
            "按类型批量取消：用户要求关闭某一类型的全部任务时设为true，并同时传task_type。例如关闭所有B站定时推送时传task_type=BILIBILI_PUSH。不要同时设置cancel_all。");

        ObjectNode typeNode = props.putObject("task_type");
        typeNode.put("type", "string");
        ArrayNode typeEnums = typeNode.putArray("enum");
        for (TaskType t : TaskType.values()) typeEnums.add(t.name());
        typeNode.put("description", "[创建时传，默认SIMPLE_TEXT；取消兜底用] 任务类型");

        ObjectNode timeStr = props.putObject("time_daily_hhmm");
        timeStr.put("type", "string");
        timeStr.put("description", "[创建时：只有 is_one_time=false 每天重复的时候必填] 每天几点发送，格式 HH:mm，例 07:30 表示每天7点半，例如 12:00、09:05。如果 is_one_time=true 单次提醒，不要填这个字段！");

        ObjectNode isOneTimeNode = props.putObject("is_one_time");
        isOneTimeNode.put("type", "boolean");
        isOneTimeNode.put("description", "[创建时★超级重要★] 只要用户说「只提醒一次」「不用每天」「只要今天」「只要明天」「就一次」「单次提醒」「就今天一次」「今天一次」「下次不用了」「今天下午XX点」「明天早上XX点」——这字段必须立刻填 true！绝对不能默认 false！只有用户明确说「每天」「每天都要」「每天重复」「每天提醒我」才填 false！");

        ObjectNode oneTimeDatetimeNode = props.putObject("one_time_datetime");
        oneTimeDatetimeNode.put("type", "string");
        oneTimeDatetimeNode.put("description", "[创建时 is_one_time=true 单次提醒时必填] 单次提醒的具体日期时间，格式随意：『今天 15:00』『明天 8:30』『2026-07-28 15:00』『7月29日 20:00』『15:00』（默认今天）都行，记得替换掉中文冒号为英文冒号。如果 is_one_time=false 每天重复，不要填这个字段！");

        ObjectNode cronNode = props.putObject("cron_expression");
        cronNode.put("type", "string");
        cronNode.put("description", "[创建可选] Spring Cron6位表达式，如果传了上面的 time_daily_hhmm 就不用传这个");

        ObjectNode paramsNode = props.putObject("params_json");
        paramsNode.put("type", "string");
        paramsNode.put("description", "[可选] 附加参数 JSON 字符串，默认传 {}");

        ObjectNode contentNode = props.putObject("message_content");
        contentNode.put("type", "string");
        contentNode.put("description", "[创建时传，强烈建议填写] 用户要求定时发送的具体消息内容文本。用户说『发送XX内容』时，把 XX 内容原样填这里，不要留空！");

        ArrayNode required = params.putArray("required");
        required.add("action");
        // user_id 不暴露给模型，由 Agent 请求上下文绑定当前微信用户。

        return root;
    }

    @Override
    public String execute(JsonNode args) throws Exception {
        String action = args.path("action").asText("");
        String userId = requestContextHolder.currentUserId();
        if (userId.isBlank()) {
            return "{\"success\":false,\"error\":\"找不到当前对话用户，请在用户消息对话中创建/取消订阅\"}";
        }

        switch (action) {
            case SUB_CREATE: return executeIdempotently(userId, action, args);
            case SUB_CANCEL: return executeIdempotently(userId, action, args);
            case SUB_LIST:   return doList(userId);
            default:         return "{\"success\":false,\"error\":\"未知 action：" + action + "\"}";
        }
    }

    private String executeIdempotently(String userId, String action, JsonNode args) throws Exception {
        Long messageId = requestContextHolder.current().messageId();
        String requestScope = messageId == null ? userId : userId + ":message:" + messageId;
        String key = idempotencyService.key(requestScope, TOOL_NAME + ":" + action, args);
        IdempotencyClaim claim = idempotencyService.claim(key, TOOL_NAME + ":" + action);
        if (!claim.acquired()) return previousExecution(key, claim.execution());
        try {
            String result = SUB_CREATE.equals(action)
                ? doCreate(userId, args, key) : doCancel(userId, args);
            JsonNode parsed = mapper.readTree(result);
            if (parsed.path("success").asBoolean(false)) {
                idempotencyService.succeed(key, result);
            } else {
                idempotencyService.fail(key, parsed.path("error")
                    .asText(parsed.path("message").asText("操作失败")));
            }
            return addIdempotencyMetadata(result, key, false);
        } catch (Exception exception) {
            idempotencyService.fail(key, exception.getMessage());
            throw exception;
        }
    }

    private String previousExecution(String key, IdempotencyExecution execution) throws Exception {
        if (execution != null && execution.getStatus() == IdempotencyStatus.SUCCEEDED
            && execution.getResult() != null && !execution.getResult().isBlank()) {
            return addIdempotencyMetadata(execution.getResult(), key, true);
        }
        ScheduledSubscription recovered = controlService.findByIdempotencyKey(key);
        if (recovered != null) {
            ObjectNode result = mapper.createObjectNode();
            result.put("success", true);
            result.put("subscription_id", recovered.getId());
            result.put("message", "检测到该订阅已创建，已恢复历史执行结果");
            result.put("idempotency_recovered", true);
            String raw = result.toString();
            idempotencyService.succeed(key, raw);
            return addIdempotencyMetadata(raw, key, true);
        }
        ObjectNode result = mapper.createObjectNode();
        result.put("success", false);
        result.put("idempotency_key", key);
        result.put("execution_status", execution == null ? "UNKNOWN" : execution.getStatus().name());
        result.put("retryable", execution == null || execution.getStatus() == IdempotencyStatus.FAILED);
        result.put("error", execution == null ? "幂等执行状态未知" : "相同操作正在执行，请勿重复提交");
        return result.toString();
    }

    private String addIdempotencyMetadata(String raw, String key, boolean replayed) throws Exception {
        ObjectNode result = (ObjectNode) mapper.readTree(raw);
        result.put("idempotency_key", key);
        result.put("execution_status", "SUCCEEDED");
        result.put("idempotency_replayed", replayed);
        return result.toString();
    }

    private String doCreate(String userId, JsonNode args, String idempotencyKey) {
        // ============== 单次提醒 还是 每天重复？ ==============
        boolean isOneTime = args.path("is_one_time").asBoolean(false);
        String oneTimeDatetime = args.path("one_time_datetime").asText("");
        String timeStr = args.path("time_daily_hhmm").asText("");
        String cron = args.path("cron_expression").asText("");
        String messageContent = args.path("message_content").asText("");

        // 读 params_json 合并
        String paramsJsonRaw = args.path("params_json").asText("{}");
        ObjectNode paramsObj;
        try {
            JsonNode parsed = mapper.readTree(paramsJsonRaw == null || paramsJsonRaw.isBlank() ? "{}" : paramsJsonRaw);
            paramsObj = parsed.isObject() ? (ObjectNode) parsed : mapper.createObjectNode();
        } catch (Exception e) {
            paramsObj = mapper.createObjectNode();
        }
        if (messageContent != null && !messageContent.isBlank()) {
            paramsObj.put("message_content", messageContent);
        }

        ScheduledSubscription sub = new ScheduledSubscription();
        sub.setUserId(userId);
        sub.setIdempotencyKey(idempotencyKey);

        // ========== 分支1：单次提醒 ==========
        String nextFireTime;
        if (isOneTime) {
            TaskType taskType;
            try {
                String tt = args.path("task_type").asText("");
                taskType = (tt != null && !tt.isBlank()) ? TaskType.valueOf(tt) : TaskType.ONE_TIME_REMINDER;
            } catch (Exception e) { taskType = TaskType.ONE_TIME_REMINDER; }
            sub.setTaskType(taskType);

            // 解析单次触发时间（北京时间时间戳）
            long fireAt = SchedulerControlService.parseOneTimeFireAt(oneTimeDatetime);
            if (fireAt <= 0) {
                // 如果 AI 没传 one_time_datetime，尝试用 time_daily_hhmm 当今天的时间
                if (!timeStr.isBlank()) {
                    fireAt = SchedulerControlService.parseOneTimeFireAt("今天 " + timeStr);
                }
                // 实在解析不出来，默认 1 分钟后触发（兜底）
                if (fireAt <= 0) fireAt = System.currentTimeMillis() + 60_000L;
            }
            paramsObj.put("is_one_time", true);
            paramsObj.put("fire_timestamp", fireAt);
            paramsObj.put("one_time_datetime", oneTimeDatetime);

            // 单次提醒 Cron 是占位符（不看），随便填个合法的
            sub.setCronExpression("0 0 0 ? * *");

            // 计算 next_fire_time（直接用 fireAt 转北京时间，比 calcNextFireTime 准100倍！）
            try {
                java.time.LocalDateTime d = java.time.LocalDateTime.ofInstant(
                    java.time.Instant.ofEpochMilli(fireAt),
                    java.time.ZoneId.of("Asia/Shanghai"));
                nextFireTime = d.format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
            } catch (Exception e) { nextFireTime = SchedulerControlService.calcNextFireTime(sub.getCronExpression()); }

        // ========== 分支2：原有的每天重复 Cron 订阅 ==========
        } else {
            TaskType taskType;
            try {
                taskType = TaskType.valueOf(args.path("task_type").asText(TaskType.SIMPLE_TEXT.name()));
            } catch (Exception e) { taskType = TaskType.SIMPLE_TEXT; }
            sub.setTaskType(taskType);

            if (cron.isBlank() && !timeStr.isBlank()) cron = SchedulerControlService.timeToDailyCron(timeStr);
            if (cron.isBlank()) cron = "0 0 9 * * ?";
            sub.setCronExpression(cron);
            nextFireTime = SchedulerControlService.calcNextFireTime(cron);
        }

        String finalParamsJson;
        try { finalParamsJson = mapper.writeValueAsString(paramsObj); } catch (Exception e) { finalParamsJson = "{}"; }
        sub.setParamsJson(finalParamsJson);

        ScheduledSubscription saved = controlService.createOrUpdate(sub);

        ObjectNode res = mapper.createObjectNode();
        res.put("success", true);
        res.put("is_one_time", isOneTime);
        if (isOneTime) {
            if (messageContent != null && !messageContent.isBlank()) {
                res.put("message", "创建【单次提醒】成功！将在北京时间 " + nextFireTime
                    + " 发送内容：" + messageContent + "（只发这一次，发完自动删除，不会每天骚扰）");
            } else {
                res.put("message", "创建【单次提醒】成功！将在北京时间 " + nextFireTime
                    + " 发送提醒消息（只发这一次）");
            }
        } else {
            if (messageContent != null && !messageContent.isBlank()) {
                res.put("message", "创建【每天重复】订阅成功！Cron=" + saved.getCronExpression()
                    + "，将每天按时间发送：" + messageContent);
            } else {
                res.put("message", "创建【每天重复】订阅成功！Cron=" + saved.getCronExpression()
                    + "，将每天按时间给用户发定时消息。");
            }
        }
        res.put("subscription_id", saved.getId());
        res.put("message_content_was_set", messageContent != null && !messageContent.isBlank());
        res.put("next_fire_time", nextFireTime); // AI 回复必须把这个时间告诉用户！
        return res.toString();
    }

    private String doCancel(String userId, JsonNode args) {
        ObjectNode res = mapper.createObjectNode();
        // 优先级1：cancel_all=true，取消用户全部订阅
        boolean cancelAll = args.path("cancel_all").asBoolean(false);
        if (cancelAll) {
            int cnt = controlService.cancelAllByUser(userId);
            res.put("success", true);
            res.put("message", "已成功取消你所有的 " + cnt + " 个订阅，后续不会再给你发任何定时消息。");
            res.put("cancel_count", cnt);
            return res.toString();
        }
        // 优先级2：按 subscription_id 精确取消（不会误删）
        boolean cancelMatchingAll = args.path("cancel_matching_all").asBoolean(false);
        if (cancelMatchingAll) {
            String taskType = args.path("task_type").asText("");
            if (taskType.isBlank()) {
                res.put("success", false);
                res.put("error", "按类型批量取消时 task_type 不能为空");
                return res.toString();
            }
            int count = controlService.cancelAllByUserAndType(userId, taskType);
            if (count < 0) {
                res.put("success", false);
                res.put("error", "未知的任务类型：" + taskType);
                return res.toString();
            }
            res.put("success", true);
            res.put("cancel_count", count);
            res.put("task_type", taskType);
            res.put("message", count == 0
                ? "当前没有启用中的该类型定时任务，无需取消。"
                : "已成功取消 " + count + " 个 " + taskType + " 类型的定时任务。");
            return res.toString();
        }
        String subId = args.path("subscription_id").asText("");
        if (subId != null && !subId.isBlank()) {
            boolean ok = controlService.cancelBySubscriptionId(subId, userId);
            res.put("success", ok);
            res.put("message", ok ? "已成功取消订阅编号 " + subId + " 的任务。"
                    : "没有找到订阅编号 " + subId + " 的可取消订阅（可能已经取消了或编号输错了）。");
            return res.toString();
        }
        // 兜底：按 task_type 取消（只会删同类型的第一条，不推荐）
        String typeStr = args.path("task_type").asText(TaskType.SIMPLE_TEXT.name());
        boolean ok = controlService.cancelByUserAndType(userId, typeStr);
        res.put("success", ok);
        res.put("message", ok ? "已成功取消指定类型的订阅。"
                : "没有找到可取消的订阅（请告诉我要取消的订阅编号，或者说「取消所有订阅」一键全清）。");
        return res.toString();
    }

    private String doList(String userId) {
        List<ScheduledSubscription> list = controlService.listByUser(userId);
        ObjectNode res = mapper.createObjectNode();
        res.put("success", true);
        ArrayNode arr = res.putArray("subscriptions");
        java.time.format.DateTimeFormatter dtf = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
        for (ScheduledSubscription s : list) {
            ObjectNode item = arr.addObject();
            String content = "";
            boolean isOneTime = false;
            long fireTimestamp = 0L;
            try {
                if (s.getParamsJson() != null && !s.getParamsJson().isBlank()) {
                    JsonNode p = mapper.readTree(s.getParamsJson());
                    if (p != null && p.isObject()) {
                        content = p.path("message_content").asText("");
                        isOneTime = p.path("is_one_time").asBoolean(false);
                        fireTimestamp = p.path("fire_timestamp").asLong(0L);
                    }
                }
            } catch (Exception ignored) {}
            String timeHint;
            String nextFire;
            if (isOneTime || s.getTaskType() == TaskType.ONE_TIME_REMINDER) {
                if (fireTimestamp > 0) {
                    java.time.LocalDateTime d = java.time.LocalDateTime.ofInstant(
                        java.time.Instant.ofEpochMilli(fireTimestamp),
                        java.time.ZoneId.of("Asia/Shanghai"));
                    timeHint = "⏰ 单次提醒 " + d.format(dtf);
                    nextFire = d.format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
                } else {
                    timeHint = "⏰ 单次提醒";
                    nextFire = "无法计算";
                }
            } else {
                timeHint = "⏰ " + SchedulerControlService.cronToTimeDesc(s.getCronExpression());
                nextFire = SchedulerControlService.calcNextFireTime(s.getCronExpression());
            }
            item.put("id", s.getId() == null ? "" : s.getId())
                .put("task_type", s.getTaskType().name())
                .put("is_one_time", isOneTime)
                .put("time_hint", timeHint)    // AI 直接拿这个展示给用户就行
                .put("next_fire_time", nextFire)
                .put("cron_expression", s.getCronExpression())
                .put("enabled", s.isEnabled())
                .put("created_at", s.getCreatedAt() == null ? 0 : s.getCreatedAt())
                .put("message_content", content);
        }
        res.put("count", list.size());
        return res.toString();
    }
}
