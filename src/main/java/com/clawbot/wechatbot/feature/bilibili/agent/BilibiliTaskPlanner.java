package com.clawbot.wechatbot.feature.bilibili.agent;

import com.clawbot.wechatbot.feature.bilibili.messaging.BilibiliCommandParser;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** 将一个B站Skill指令拆成有限、白名单化的领域任务。 */
@Component
public final class BilibiliTaskPlanner {
    private static final Pattern CONNECTOR = Pattern.compile(
        "\\s*(?:，|,|；|;|然后|并且|以及|同时|再帮我|再)\\s*");

    public List<BilibiliTask> plan(String instruction, int maxTasks) {
        if (instruction == null || instruction.isBlank()) return List.of();
        String[] clauses = CONNECTOR.split(instruction.trim());
        List<BilibiliTask> tasks = new ArrayList<>();
        String latestSelectionTask = null;

        for (String rawClause : clauses) {
            String clause = rawClause == null ? "" : rawClause.trim();
            if (clause.isBlank()) continue;
            BilibiliTaskType type = classify(clause);
            if (type == null) {
                if (clauses.length > 1) {
                    throw new IllegalArgumentException(
                        "暂不支持该B站子任务：" + clause);
                }
                continue;
            }
            if (tasks.size() >= Math.max(1, maxTasks)) {
                throw new IllegalArgumentException(
                    "B站子任务超过上限，当前最多处理 " + Math.max(1, maxTasks) + " 项");
            }
            String id = "bili-task-" + (tasks.size() + 1);
            List<String> dependencies = type == BilibiliTaskType.SUBSCRIBE_CONTENT
                && refersToSelection(clause) && latestSelectionTask != null
                ? List.of(latestSelectionTask) : List.of();
            BilibiliTask task = new BilibiliTask(
                id, tasks.size(), type, clause, dependencies);
            tasks.add(task);
            if (type == BilibiliTaskType.SEARCH_CONTENT
                || type == BilibiliTaskType.RECOMMEND_CONTENT) {
                latestSelectionTask = id;
            }
        }

        // 没有发生有效拆分时，仍允许现有命令处理器识别完整指令。
        if (tasks.isEmpty()) {
            BilibiliTaskType type = classify(instruction.trim());
            if (type != null) {
                tasks.add(new BilibiliTask(
                    "bili-task-1", 0, type, instruction.trim(), List.of()));
            }
        }
        return List.copyOf(tasks);
    }

    private BilibiliTaskType classify(String instruction) {
        BilibiliCommandParser.CmdType command =
            BilibiliCommandParser.parse(instruction).type();
        return switch (command) {
            case SEARCH_BY_TITLE -> BilibiliTaskType.SEARCH_CONTENT;
            case TODAY_RECOMMEND_ANIME, TODAY_RECOMMEND_MOVIE,
                 TODAY_RECOMMEND_SERIES, RAG_QA, RAG_SIMILAR ->
                BilibiliTaskType.RECOMMEND_CONTENT;
            case SUBSCRIBE_BY_INDEX, SUBSCRIBE_BY_URL, SUBSCRIBE_BY_TITLE ->
                BilibiliTaskType.SUBSCRIBE_CONTENT;
            case CONFIGURE_DAILY_RECOMMENDATION, SET_PUSH_TIME,
                 SET_MIN_RATING, SET_RECOMMEND_COUNT,
                 SET_WEEKDAY_PUSH_POLICY, TOGGLE_PUSH ->
                BilibiliTaskType.CONFIGURE_PUSH;
            default -> null;
        };
    }

    private boolean refersToSelection(String instruction) {
        return instruction.matches(".*(?:第?[一二两三四五六七八九十\\d]+个|第一部|第一个).*?");
    }
}
