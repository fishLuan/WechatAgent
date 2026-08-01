package com.clawbot.wechatbot.web;

import com.clawbot.wechatbot.scheduler.controller.SchedulerControlService;
import com.clawbot.wechatbot.scheduler.core.TaskSchedulerCore;
import com.clawbot.wechatbot.scheduler.model.ScheduledSubscription;
import com.clawbot.wechatbot.scheduler.model.TaskType;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 可视化控制台：定时任务管理 API。
 * 复用现有 SchedulerControlService / TaskSchedulerCore / MongoDB 持久化，
 * 控制台只是多一个 HTTP 入口（与 AI function-calling 走同一套调度）。
 */
@RestController
@RequestMapping("/api/tasks")
public class TaskController {

    private final MongoTemplate mongoTemplate;
    private final TaskSchedulerCore schedulerCore;
    private final SchedulerControlService control;
    private final ObjectMapper mapper;

    public TaskController(
        MongoTemplate mongoTemplate,
        TaskSchedulerCore schedulerCore,
        SchedulerControlService control,
        ObjectMapper mapper
    ) {
        this.mongoTemplate = mongoTemplate;
        this.schedulerCore = schedulerCore;
        this.control = control;
        this.mapper = mapper;
    }

    /** 全部任务列表 */
    @GetMapping
    public List<ScheduledSubscription> list() {
        List<ScheduledSubscription> all = mongoTemplate.findAll(ScheduledSubscription.class);
        all.sort(Comparator.comparing(ScheduledSubscription::getCreatedAt,
            Comparator.nullsLast(Long::compareTo)).reversed());
        return all;
    }

    /** 新建任务 */
    @PostMapping
    public ResponseEntity<?> create(@RequestBody Map<String, Object> body) {
        try {
            validate(body);
            ScheduledSubscription sub = buildSubscription(body, null);
            ScheduledSubscription saved = control.createOrUpdate(sub);
            return ResponseEntity.ok(saved);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(error(e));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(error(e));
        }
    }

    /** 更新任务（保留 id） */
    @PutMapping("/{id}")
    public ResponseEntity<?> update(@PathVariable String id, @RequestBody Map<String, Object> body) {
        ScheduledSubscription existing = mongoTemplate.findById(id, ScheduledSubscription.class);
        if (existing == null) return ResponseEntity.notFound().build();
        try {
            validate(body);
            ScheduledSubscription sub = buildSubscription(body, existing);
            ScheduledSubscription saved = control.createOrUpdate(sub);
            return ResponseEntity.ok(saved);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(error(e));
        }
    }

    /** 启用 / 停用 */
    @PostMapping("/{id}/toggle")
    public ResponseEntity<?> toggle(@PathVariable String id) {
        ScheduledSubscription sub = mongoTemplate.findById(id, ScheduledSubscription.class);
        if (sub == null) return ResponseEntity.notFound().build();
        boolean next = !sub.isEnabled();
        if (next) {
            // 启用前校验：非法 cron / 过期的单次提醒不允许启用，避免「显示启用但永远不会触发」的僵尸任务
            String invalid = invalidReason(sub);
            if (invalid != null) {
                return ResponseEntity.badRequest().body(Map.of("error", invalid));
            }
            sub.setEnabled(true);
            schedulerCore.register(sub);
        } else {
            schedulerCore.cancel(sub.getId());
            sub.setEnabled(false);
        }
        mongoTemplate.save(sub);
        return ResponseEntity.ok(sub);
    }

    /** 删除（先停调度，再删文档） */
    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable String id) {
        ScheduledSubscription sub = mongoTemplate.findById(id, ScheduledSubscription.class);
        if (sub == null) return ResponseEntity.notFound().build();
        schedulerCore.cancel(id);
        mongoTemplate.remove(Query.query(Criteria.where("_id").is(id)), ScheduledSubscription.class);
        Map<String, Object> ok = new LinkedHashMap<>();
        ok.put("deleted", true);
        ok.put("id", id);
        return ResponseEntity.ok(ok);
    }

    /** 校验 cron 并返回人类可读的下次触发时间 */
    @PostMapping("/preview")
    public ResponseEntity<?> preview(@RequestBody Map<String, Object> body) {
        try {
            Map<String, Object> out = new LinkedHashMap<>();
            String cron = resolveCron(body);
            out.put("cron", cron);
            out.put("nextFire", SchedulerControlService.calcNextFireTime(cron));
            out.put("desc", SchedulerControlService.cronToTimeDesc(cron));
            return ResponseEntity.ok(out);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(error(e));
        }
    }

    // ---------- 校验 ----------

    /** 保存前校验：cron 合法性、单次提醒时间必须晚于当前 */
    private void validate(Map<String, Object> body) throws IllegalArgumentException {
        String taskTypeStr = str(body, "taskType");
        TaskType tt = null;
        if (taskTypeStr != null && !taskTypeStr.isBlank()) {
            try {
                tt = TaskType.valueOf(taskTypeStr.trim().toUpperCase());
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("未知任务类型：" + taskTypeStr + "（可选 SIMPLE_TEXT / ONE_TIME_REMINDER）");
            }
        }
        if (tt == TaskType.ONE_TIME_REMINDER) {
            Object fireTs = body.get("fireTimestamp");
            if (!(fireTs instanceof Number n) || n.longValue() <= 0) {
                throw new IllegalArgumentException("单次提醒必须提供 fireTimestamp（毫秒时间戳）");
            }
            if (n.longValue() <= System.currentTimeMillis()) {
                throw new IllegalArgumentException("提醒时间必须晚于当前时间");
            }
        } else {
            String cron = resolveCron(body);
            if (!cron.isBlank()) {
                String next = SchedulerControlService.calcNextFireTime(cron);
                if (next.startsWith("Cron 表达式错误") || next.equals("无法计算")) {
                    throw new IllegalArgumentException("Cron 表达式无效：" + cron + "（如 每天9点 = 0 0 9 * * ?）");
                }
            }
        }
    }

    /** 启用前校验：返回非法原因，null 表示可启用 */
    private String invalidReason(ScheduledSubscription sub) {
        if (sub.getTaskType() == TaskType.ONE_TIME_REMINDER) {
            try {
                Map<?, ?> p = mapper.readValue(sub.getParamsJson() == null ? "{}" : sub.getParamsJson(), Map.class);
                Object f = p.get("fire_timestamp");
                if (f instanceof Number n && n.longValue() <= System.currentTimeMillis()) {
                    return "该单次提醒的触发时间已过期，无法启用（请删除后新建）";
                }
            } catch (Exception ignored) {
                return "该任务参数解析失败，无法启用";
            }
        } else {
            String cron = sub.getCronExpression();
            if (cron == null || cron.isBlank()) return "任务缺少 Cron 表达式，无法启用";
            String next = SchedulerControlService.calcNextFireTime(cron);
            if (next.startsWith("Cron 表达式错误") || next.equals("无法计算")) {
                return "Cron 表达式无效：" + cron;
            }
        }
        return null;
    }

    // ---------- 组装 ----------

    private ScheduledSubscription buildSubscription(Map<String, Object> body, ScheduledSubscription existing)
        throws Exception {
        ScheduledSubscription sub = existing == null ? new ScheduledSubscription() : existing;

        String taskTypeStr = str(body, "taskType");
        if (taskTypeStr != null && !taskTypeStr.isBlank()) {
            sub.setTaskType(TaskType.valueOf(taskTypeStr.trim().toUpperCase()));
        } else if (sub.getTaskType() == null) {
            sub.setTaskType(TaskType.SIMPLE_TEXT);
        }

        String userId = str(body, "userId");
        if (userId != null && !userId.isBlank()) sub.setUserId(userId);
        if (sub.getUserId() == null || sub.getUserId().isBlank()) sub.setUserId("console-admin");

        String cron = resolveCron(body);
        if (!cron.isBlank()) sub.setCronExpression(cron);
        if (sub.getCronExpression() == null || sub.getCronExpression().isBlank()) {
            sub.setCronExpression("0 0 9 * * ?");
        }

        // paramsJson：message_content（必须）+ 单次提醒的 fire_timestamp
        Map<String, Object> params = new LinkedHashMap<>();
        try {
            if (sub.getParamsJson() != null && !sub.getParamsJson().isBlank()) {
                @SuppressWarnings("unchecked")
                Map<String, Object> parsed = mapper.readValue(sub.getParamsJson(), Map.class);
                params = parsed;
            }
        } catch (Exception ignored) {
            // 旧的脏 JSON 直接覆盖
        }
        String content = str(body, "messageContent");
        if (content != null && !content.isBlank()) params.put("message_content", content);
        if (isOneTimeRequest(sub, body)) {
            Object fireTs = body.get("fireTimestamp");
            if (fireTs instanceof Number n) {
                params.put("fire_timestamp", n.longValue());
                params.put("already_fired", false);
            }
        }
        if (sub.getTaskType() == TaskType.BILIBILI_PUSH) {
            String contentType = str(body, "contentType");
            if (contentType != null && !contentType.isBlank()) {
                params.put("content_type", contentType.trim().toUpperCase());
            }
            Object count = body.get("count");
            if (count instanceof Number n && n.intValue() > 0) {
                params.put("count", n.intValue());
            }
        }
        sub.setParamsJson(mapper.writeValueAsString(params));

        if (sub.getCreatedAt() == null) sub.setCreatedAt(System.currentTimeMillis());
        return sub;
    }

    /** 单次任务：taskType 是一次性提醒，或 body 里带了 fireTimestamp */
    private boolean isOneTimeRequest(ScheduledSubscription sub, Map<String, Object> body) {
        if (sub.getTaskType() == TaskType.ONE_TIME_REMINDER) return true;
        return body.get("fireTimestamp") instanceof Number;
    }

    private String resolveCron(Map<String, Object> body) {
        String daily = str(body, "timeDailyHhmm");
        if (daily != null && !daily.isBlank()) {
            return SchedulerControlService.timeToDailyCron(daily);
        }
        String cron = str(body, "cronExpression");
        return cron == null ? "" : cron.trim();
    }

    private String str(Map<String, Object> body, String key) {
        Object v = body.get(key);
        return v == null ? null : String.valueOf(v);
    }

    private Map<String, Object> error(Exception e) {
        Map<String, Object> err = new LinkedHashMap<>();
        err.put("error", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        return err;
    }
}
