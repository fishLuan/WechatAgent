package com.clawbot.wechatbot.scheduler;

import com.clawbot.wechatbot.service.ChatService;
import com.clawbot.wechatbot.service.agent.AgentOrchestrator;
import com.github.wechat.ilink.sdk.ILinkClient;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

public class TaskCommandRouter {

    private static final long ORDER_CACHE_TTL_MS = 5 * 60 * 1000L;
    private static final Pattern JSON_BLOCK = Pattern.compile("```json\\s*(\\{[\\s\\S]*?\\})\\s*```|(\\{[\\s\\S]*?\"task\"[\\s\\S]*?\\})", Pattern.CASE_INSENSITIVE);

    private final AgentTaskScheduler scheduler;
    private final RemindCommandParser parser;
    private final WeChatMessageSender sender;
    private final ChatService chatService;
    private final AgentOrchestrator agentOrchestrator;

    private final ConcurrentHashMap<String, TaskOrderEntry> orderCache = new ConcurrentHashMap<>();
    private record TaskOrderEntry(List<String> taskIds, long expireAtMs) {}

    public TaskCommandRouter(
        AgentTaskScheduler scheduler,
        RemindCommandParser parser,
        WeChatMessageSender sender,
        ChatService chatService,
        AgentOrchestrator agentOrchestrator
    ) {
        this.scheduler = scheduler;
        this.parser = parser;
        this.sender = sender;
        this.chatService = chatService;
        this.agentOrchestrator = agentOrchestrator;
    }

    public static boolean isTaskIntent(String text) {
        if (text == null) return false;
        String t = text.trim();
        String tl = t.toLowerCase();
        if (tl.startsWith("/remind") || tl.equals("/tasks") || tl.startsWith("/cancel") || tl.equals("/cancel-all")) {
            return true;
        }
        if (t.contains("取消所有") && (t.contains("任务") || t.contains("提醒"))) return true;
        if ((t.startsWith("取消") || t.contains("取消")) &&
            ((t.contains("第") && t.contains("个")) || t.contains("任务") || t.contains("提醒"))) {
            return true;
        }
        if (t.equals("我的任务") || t.equals("查看任务") || t.equals("所有任务")) return true;

        boolean hasActionKeyword = t.contains("提醒") || t.contains("叫我") || t.contains("通知我") || t.contains("到点")
            || t.contains("发给我") || t.contains("推送") || t.contains("收到一条") || t.contains("发消息给我")
            || t.contains("告诉我") || t.contains("提示我");
        boolean hasTimeMarker = t.matches(".*(每天|每日|每周|每周一|每周二|每周三|每周四|每周五|每周六|每周日|早上|上午|中午|下午|晚上|今晚|凌晨|明天|明早|后天).*")
            || t.matches(".*\\d+\\s*(秒|分|分钟|时|小时|天|日|点|点钟|分).*")
            || t.matches(".*\\d{1,2}:\\d{2}.*");
        if (hasActionKeyword && hasTimeMarker) {
            System.out.println("[SCHEDULER-ROUTER] isTaskIntent=true（动作词+时间词命中）：" + t);
            return true;
        }

        // 兜底：只要同时出现「周期词」+「精确时间(HH:MM)」+「消息/发送」这种动作，直接算任务意图
        if (hasTimeMarker
            && t.matches(".*\\d{1,2}:\\d{2}.*")
            && (t.contains("每天") || t.contains("每日") || t.contains("每周"))
            && (t.contains("消息") || t.contains("发送") || t.contains("发") || t.contains("推送") || t.contains("提醒") || t.contains("告诉"))) {
            System.out.println("[SCHEDULER-ROUTER] isTaskIntent=true（周期词+HH:MM+动作 兜底命中）：" + t);
            return true;
        }
        return false;
    }

    public void handle(ILinkClient client, String from, String rawText) {
        String t = rawText.trim().toLowerCase();
        String original = rawText.trim();
        try {
            // ⚠️ 列/取消命令 要先判断，**绝对不能**放在「创建提醒」后面，不然「我的任务」会被当成提醒内容！
            if (t.equals("/tasks") || original.equals("我的任务") || original.equals("查看任务") || original.equals("所有任务")) {
                handleList(client, from);
            }
            else if (t.equals("/cancel-all") || original.equals("取消所有任务") || original.equals("取消全部任务")
                || original.equals("取消所有提醒") || original.equals("取消全部提醒")) {
                handleCancelAll(client, from);
            }
            else if (t.startsWith("/cancel") || (original.contains("取消") &&
                ((original.contains("第") && original.contains("个")) || original.contains("任务") || original.contains("提醒"))
                && !original.contains("所有") && !original.contains("全部"))) {
                handleCancelByIndex(client, from, rawText);
            }
            else if (t.startsWith("/remind ") || isCreateRemindIntent(original)) {
                handleCreate(client, from, rawText);
            }
            else {
                // 最后兜底：如果 TextMessageHandler 把这条放进来了（isTaskIntent 返回 true）
                // 但上面没命中任何命令 → 当成创建提醒试一次，不行就直接回错，**绝不默认创建 20:00 的任务**
                RemindCommandParser.ParsedResult r = parser.parse(from, rawText);
                if (r.success()) handleCreate(client, from, rawText);
                else {
                    safeSend(client, from,
                        "❌ 没听懂这条任务指令：" + original
                            + "\n可直接说：「1分钟后提醒我关水」「每天 8 点叫我起床」「我的任务」「取消第2个」");
                }
            }
        } catch (Exception e) {
            System.err.println("[SCHEDULER-ROUTER] handle 抛异常：" + e.getMessage());
            e.printStackTrace();
            safeSend(client, from, "❌ 任务管理失败：" + e.getMessage());
        }
    }

    private static boolean isCreateRemindIntent(String raw) {
        if (raw == null) return false;
        return isTaskIntent(raw);
    }

    // ====================================
    // 1. 创建提醒（硬解析 -> 失败就调大模型）
    // ====================================

    private void handleCreate(ILinkClient client, String from, String raw) {
        if (!scheduler.isRunning()) {
            safeSend(client, from, "⚠️ 调度器未启动，暂时无法设置提醒");
            return;
        }
        if (!sender.isReady()) {
            safeSend(client, from, "⚠️ 微信还在登录中，稍等几秒再试～");
            return;
        }
        System.out.println("[SCHEDULER-ROUTER] 阶段1 硬解析：" + raw);
        RemindCommandParser.ParsedResult r = parser.parse(from, raw);
        if (!r.success() && (chatService != null && chatService.isConfigured())) {
            System.out.println("[SCHEDULER-ROUTER] 阶段1 失败，阶段2 调大模型解析...");
            r = parseWithAgent(from, raw);
            System.out.println("[SCHEDULER-ROUTER] 阶段2 大模型结果：success=" + r.success()
                + (r.success() ? " type=" + r.type() + " time=" + r.scheduleParams() + " msg=" + r.message()
                               : " err=" + r.errorMessage()));
        }
        if (!r.success()) {
            safeSend(client, from, "❌ " + r.errorMessage());
            return;
        }
        ZoneId tz = ZoneId.of("Asia/Shanghai");
        // ⚠️ 最后兜底再清一次「提醒我/叫我」等动作词，双保险，绝对不允许 message 里残留「提醒我」
        String msg = RemindCommandParser.cleanMessageVerbs(r.message());
        if (msg == null || msg.isBlank()) {
            msg = RemindCommandParser.cleanMessageVerbs(raw);
            if (msg.isBlank()) msg = "记得做事情";
        }
        String taskName = msg.length() > 15 ? msg.substring(0, 15) + "…" : msg;
        String id = switch (r.type()) {
            case ONCE -> scheduler.scheduleOnce(from, taskName, msg,
                (Instant) r.scheduleParams(), tz);
            case CRON -> scheduler.scheduleCron(from, taskName, msg,
                (String) r.scheduleParams(), tz);
            case FIXED_DELAY -> scheduler.scheduleFixedDelay(from, taskName, msg,
                (Duration) r.scheduleParams(), tz);
        };
        Optional<ScheduledTask> saved = scheduler.getTask(id);
        String confirm = saved.map(this::fmtConfirmLine).orElse(
            "✅ 已设置提醒：「" + r.message() + "」"
        );
        safeSend(client, from, confirm);
    }

    private RemindCommandParser.ParsedResult parseWithAgent(String userId, String raw) {
        try {
            String prompt = """
                你是一个定时任务抽取助手。从下面用户的话里提取 3 个字段，只输出合法 JSON，不要其他文字（不要说明、不要代码块外面的解释）：
                - task: "once" 一次性任务 | "cron" 每天/每周固定时间周期 | "fixed_delay" 每隔N分钟/小时重复
                【强规则 1】只要原话出现「后/以后/之后」或相对时间表达（比如「60秒后」「1分钟后」「3天后」）= 一定是 "once" 一次性，绝对不能写成 fixed_delay/cron 循环！例：「60秒后喝水」= task="once"，time=现在+60秒后的绝对时间
                【强规则 2】「每隔X」「每X分钟」「每小时」这种明确重复的 = 才是 fixed_delay / cron
                - time: 任务执行时间参数
                  * 一次性(task=once)：ISO8601 格式字符串，时区 Asia/Shanghai，比如 2026-07-28T20:00:00+08:00
                  * 每天/每周(task=cron)：合法 6 段 cron 表达式（秒 分 时 日 月 周），示例：
                    - 每天 12:01 = "0 1 12 * * ?"
                    - 每天 下午 13:45 = "0 45 13 * * ?"
                    - 每周一上午 10 点 = "0 0 10 ? * MON"
                    - 每天晚上 22:30 = "0 30 22 * * ?"
                  * 每隔N(task=fixed_delay)：字符串 "30m"、"2h"、"1d" 这种（m=分钟 h=小时 d=天，不能小于 1 分钟）
                - message: 到点要发的消息内容，原样保留用户说的话，不要包含时间词（秒/分/小时/天/后/每天/点）和动作词（提醒我/发给我/叫我），不要加额外话
                用户说：
                %s
                只输出 JSON：""".formatted(raw);
            String reply;
            if (agentOrchestrator != null && agentOrchestrator.isConfigured()) {
                reply = agentOrchestrator.execute(prompt, "").text();
            } else {
                reply = chatService.chat(prompt, "");
            }
            if (reply == null || reply.isBlank()) {
                return fail("大模型没返回结果，换个说法再试试（示例：每天 12:01 提醒我你好）");
            }
            Matcher mb = JSON_BLOCK.matcher(reply);
            String jsonStr = mb.find()
                ? (mb.group(1) != null ? mb.group(1) : mb.group(2))
                : reply;
            return RemindCommandParser.fromJson(jsonStr);
        } catch (Exception e) {
            return fail("大模型解析异常：" + e.getMessage() + "\n直接说人话示例：「每天 12:01 给我发你好」");
        }
    }

    private static RemindCommandParser.ParsedResult fail(String msg) {
        return new RemindCommandParser.ParsedResult(false, null, null, null, msg);
    }

    private String fmtConfirmLine(ScheduledTask t) {
        String typeZh = switch (t.type()) {
            case ONCE -> "一次性";
            case CRON -> "周期";
            case FIXED_DELAY -> "循环";
        };
        String when = switch (t.type()) {
            case ONCE -> fmtInstant(t.executeAt() != null ? t.executeAt() : Instant.now(), t.timezone());
            case CRON -> humanizeCron(t.cronExpression());
            case FIXED_DELAY -> "每隔 " + fmtDuration(t.fixedDelay());
        };
        return """
            ✅ 已设置提醒：
              📌 内容：%s
              🎯 类型：%s
              ⏱ 时间：%s
              📇 任务ID：%s
            用 /tasks 查看所有任务，/cancel <序号> 取消""".formatted(
                t.message(), typeZh, when, shortId(t.id())
            );
    }

    /** 把常见的 6 段 cron 翻译成中文，翻译不出来才退回原表达式（ScheduledTask.shortDescription 会静态调用） */
    public static String humanizeCronStatic(String cron) {
        if (cron == null || cron.isBlank()) return "未设置";
        String[] p = cron.trim().split("\\s+");
        if (p.length < 6) return "Cron " + cron;
        try {
            int sec = Integer.parseInt(p[0]);
            int min = Integer.parseInt(p[1]);
            String hour = p[2];
            String dayOfMonth = p[3];
            String month = p[4];
            String week = p.length >= 6 ? p[5] : "?";
            if (sec == 0 && "*".equals(dayOfMonth) && "*".equals(month) && ("?".equals(week) || "*".equals(week)) && isNumeric(hour)) {
                int h = Integer.parseInt(hour);
                if (h >= 0 && h <= 23 && min >= 0 && min <= 59) {
                    String period = h < 6 ? "凌晨" : h < 12 ? "上午" : h < 13 ? "中午" : h < 18 ? "下午" : "晚上";
                    return String.format("每天 %s %02d:%02d", period, h, min);
                }
            }
            if (sec == 0 && "?".equals(dayOfMonth) && "*".equals(month) && week != null && isNumeric(hour)) {
                int h = Integer.parseInt(hour);
                String weekZh = switch (week.toUpperCase()) {
                    case "MON", "1", "2" -> "每周一";
                    case "TUE", "3" -> "每周二";
                    case "WED", "4" -> "每周三";
                    case "THU", "5" -> "每周四";
                    case "FRI", "6" -> "每周五";
                    case "SAT", "7" -> "每周六";
                    case "SUN" -> "每周日";
                    default -> null;
                };
                if (weekZh != null && min >= 0 && min <= 59 && h >= 0 && h <= 23) {
                    return String.format("%s %02d:%02d", weekZh, h, min);
                }
            }
        } catch (Exception ignored) {}
        return "Cron " + cron;
    }

    private String humanizeCron(String cron) { return humanizeCronStatic(cron); }

    private static boolean isNumeric(String s) {
        if (s == null || s.isEmpty()) return false;
        for (int i = 0; i < s.length(); i++) if (!Character.isDigit(s.charAt(i))) return false;
        return true;
    }

    // ====================================
    // 2. 列任务（有序号缓存）
    // ====================================

    private void handleList(ILinkClient client, String from) {
        List<ScheduledTask> tasks = scheduler.listTasks(from);
        if (tasks.isEmpty()) {
            orderCache.remove(from);
            safeSend(client, from, "📭 目前没有定时任务\n试试：/remind 1m 测试一下");
            return;
        }
        List<String> ids = tasks.stream().map(ScheduledTask::id).collect(Collectors.toList());
        orderCache.put(from, new TaskOrderEntry(ids, System.currentTimeMillis() + ORDER_CACHE_TTL_MS));

        String body = IntStream.range(0, tasks.size())
            .mapToObj(i -> {
                ScheduledTask t = tasks.get(i);
                String status = switch (t.status()) {
                    case PENDING -> "待执行";
                    case RUNNING -> "🟡 执行中";
                    case COMPLETED -> "✅ 已完成";
                    case CANCELLED -> "❌ 已取消";
                    case FAILED -> "⚠️ 失败";
                };
                return "%d. %s\n      状态：%s".formatted(i + 1, t.shortDescription(), status);
            })
            .collect(Collectors.joining("\n"));

        String header = "📋 你的定时任务（共 " + tasks.size() + " 个）\n";
        String footer = "\n\n💡 取消：/cancel <数字>，例：/cancel 2\n全部取消：/cancel-all\n（序号 5 分钟内有效）";
        safeSend(client, from, header + body + footer);
    }

    // ====================================
    // 3. 按序号取消
    // ====================================

    private void handleCancelByIndex(ILinkClient client, String from, String raw) {
        Integer idx = RemindCommandParser.extractCancelIndex(raw);
        if (idx == null) {
            safeSend(client, from, "❓ 取消命令示例：/cancel 2（取消第2个任务）\n先发 /tasks 看序号");
            return;
        }
        TaskOrderEntry entry = orderCache.get(from);
        if (entry == null || System.currentTimeMillis() > entry.expireAtMs()) {
            orderCache.remove(from);
            safeSend(client, from, "⏰ 任务列表序号过期啦，先发一次 /tasks 刷新序号再取消～");
            return;
        }
        if (idx < 1 || idx > entry.taskIds().size()) {
            safeSend(client, from,
                "❌ 序号范围不对，当前有 " + entry.taskIds().size() + " 个任务，请输入 1~" + entry.taskIds().size());
            return;
        }
        String taskId = entry.taskIds().get(idx - 1);
        AgentTaskScheduler.CancelResult r = scheduler.cancelTask(taskId);
        orderCache.remove(from);
        if (r.success()) {
            safeSend(client, from,
                "✅ 已取消：「" + (r.task() != null ? r.task().name() : "") + "」");
        } else {
            safeSend(client, from, "❌ 取消失败：" + r.reason());
        }
    }

    // ====================================
    // 4. 取消所有
    // ====================================

    private void handleCancelAll(ILinkClient client, String from) {
        AgentTaskScheduler.CancelAllResult r = scheduler.cancelAll(from);
        orderCache.remove(from);
        StringBuilder sb = new StringBuilder("🧹 批量取消完成：\n");
        sb.append("  ✅ 成功取消 ").append(r.success()).append(" 个\n");
        if (r.skipped() > 0) sb.append("  ⏭ 本来就是已完成/取消 ").append(r.skipped()).append(" 个\n");
        if (r.running() > 0) sb.append("  🟡 执行中未取消 ").append(r.running()).append(" 个（稍后会自动完成）\n");
        if (r.failed() > 0) sb.append("  ❌ 取消失败 ").append(r.failed()).append(" 个\n");
        sb.append("\n查看：/tasks");
        safeSend(client, from, sb.toString());
    }

    // ====================================
    // 辅助
    // ====================================

    private void safeSend(ILinkClient client, String to, String text) {
        try {
            long typingMillis = Math.min(2000, 300L + text.length() * 20L);
            client.sendTextWithTyping(to, text, typingMillis);
        } catch (Exception e) {
            System.err.println("[TASK-ROUTER] 发送失败: " + e.getMessage());
        }
    }

    private static String fmtInstant(Instant instant, ZoneId tz) {
        var zdt = instant.atZone(tz);
        return String.format("%04d-%02d-%02d %02d:%02d",
            zdt.getYear(), zdt.getMonthValue(), zdt.getDayOfMonth(),
            zdt.getHour(), zdt.getMinute());
    }

    private static String fmtDuration(Duration d) {
        long m = d.toMinutes();
        if (m < 60) return m + "分钟";
        long h = m / 60, rm = m % 60;
        if (h < 24) return h + "小时" + (rm > 0 ? rm + "分钟" : "");
        long days = h / 24, rh = h % 24;
        return days + "天" + (rh > 0 ? rh + "小时" : "");
    }

    private static String shortId(String id) {
        return id == null || id.length() < 8 ? id : id.substring(0, 8);
    }
}