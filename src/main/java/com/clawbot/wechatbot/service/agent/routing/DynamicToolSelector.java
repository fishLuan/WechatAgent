package com.clawbot.wechatbot.service.agent.routing;

import com.clawbot.wechatbot.intent.IntentResult;
import com.clawbot.wechatbot.intent.IntentType;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/** Selects the smallest safe function-tool set for a request or Agent task. */
public final class DynamicToolSelector {

    public Optional<FastRoute> fastRoute(IntentResult intent, String userText) {
        if (intent == null) return Optional.empty();
        if (intent.type() == IntentType.MULTI_TASK || requiresAgentPlanning(intent, userText)) {
            return Optional.empty();
        }
        Set<String> tools = selectTools(userText);
        if (tools.size() > 1) return Optional.empty();
        if (intent.type() == IntentType.TOOL_QUERY) {
            return tools.size() == 1
                ? Optional.of(new FastRoute(tools, "explicit-single-tool"))
                : Optional.empty();
        }
        if (intent.type() != IntentType.GENERAL_CHAT) return Optional.empty();
        if (tools.size() == 1) {
            return Optional.of(new FastRoute(tools, "recognized-single-tool"));
        }
        if (looksLikeMultiTask(userText) || looksLikeUnresolvedExternalQuery(userText)) {
            return Optional.empty();
        }
        return Optional.of(new FastRoute(Set.of(), "general-chat"));
    }

    /** Empty means no confident restriction; callers should preserve all tools. */
    public Optional<Set<String>> toolsForTask(String instruction) {
        Set<String> selected = selectTools(instruction);
        return selected.isEmpty() ? Optional.empty() : Optional.of(selected);
    }

    Set<String> selectTools(String input) {
        String text = normalize(input);
        LinkedHashSet<String> tools = new LinkedHashSet<>();
        if (containsAny(text, "天气", "气温", "温度", "降雨", "下雨")) {
            tools.add("get_weather");
        }
        if (containsAny(text, "汇率", "换算", "兑换", "币种", "人民币", "美元",
            "欧元", "日元", "港币")) {
            tools.add("convert_currency");
        }
        if (containsAny(text, "新闻", "头条", "热搜")) tools.add("get_news");
        if (containsAny(text, "现在几点", "几点了", "当前时间", "当前日期", "今天几号")) {
            tools.add("get_current_time");
        }
        if (containsAny(text, "身份证", "身份证号")) tools.add("validate_id_card");
        if (containsAny(text, "路线", "导航", "怎么走", "驾车方案", "出行方案")) {
            tools.add("get_route_plan");
        }
        if (containsAny(text, "生辰八字", "八字", "算命", "运势")) {
            tools.add("calculate_bazi_fortune");
        }
        if (containsAny(text, "星座", "生肖")) tools.add("calculate_zodiac_info");
        if (containsAny(text, "定时", "提醒我", "每天推送", "每周推送", "取消提醒")) {
            tools.add("scheduler_manage");
        }
        if (containsAny(text, "联网搜索", "网上搜索", "搜索网页", "实时搜索")) {
            tools.add("web_search");
        }
        if (text.matches(".*https?://\\S+.*")) {
            tools.add("check_url_safety");
            tools.add("extract_web_page");
        }
        return Set.copyOf(tools);
    }

    /**
     * Returns true only when the request needs Planner/Skill execution. Merely
     * mentioning a domain such as anime, books or Excel is not an operation.
     */
    public boolean requiresAgentPlanning(IntentResult intent, String input) {
        if (intent != null) {
            switch (intent.type()) {
                case MULTI_TASK, BILIBILI_SUBSCRIBE_URL,
                     BILIBILI_SUBSCRIBE_INDEX, BILIBILI_SUBSCRIBE_TITLE,
                     BILIBILI_SEARCH_TITLE, BILIBILI_MARK_TITLE,
                     BILIBILI_RECOMMEND, WEREAD_QUERY,
                     CONTENT_SEARCH_AMBIGUOUS, IMAGE_GENERATION,
                     DOCUMENT_GENERATION -> {
                    return true;
                }
                default -> { }
            }
        }
        String text = normalize(input);
        if (containsAny(text,
            "生成图片", "生成一张图", "画一张", "画个", "绘制图片",
            "语音回复", "男声回复", "女声回复", "再读一遍", "朗读出来")) {
            return true;
        }
        if (containsAny(text, "生成文档", "生成word", "生成pdf", "导出word",
            "导出pdf", "写入文档", "制作文档")) {
            return true;
        }
        if (containsAny(text, "生成excel", "导出excel", "生成表格", "制作表格",
            "写入excel", "整理成表格")) {
            return true;
        }
        if (containsAny(text, "订阅", "追更", "取消订阅", "标记看过")) {
            return true;
        }
        boolean mediaDomain = containsAny(text,
            "b站", "动漫", "番剧", "电视剧", "电影", "影视", "作品");
        boolean mediaAction = containsAny(text,
            "搜索", "搜一下", "查找", "找一下", "找个", "推荐", "推送", "看看");
        if (mediaDomain && mediaAction) return true;

        boolean bookDomain = containsAny(text,
            "微信读书", "书", "图书", "小说", "作者");
        boolean bookAction = containsAny(text,
            "搜书", "找书", "搜索", "搜一下", "查找", "找一下", "推荐", "推书");
        return bookDomain && bookAction;
    }

    private boolean looksLikeMultiTask(String input) {
        String text = normalize(input);
        return containsAny(text, "同时", "并且", "然后", "以及", "再帮我", "还要");
    }

    private boolean looksLikeUnresolvedExternalQuery(String input) {
        String text = normalize(input);
        return containsAny(text, "最新", "实时", "联网", "网上查", "搜索一下", "帮我查");
    }

    private String normalize(String input) {
        return input == null ? "" : input.trim().toLowerCase(Locale.ROOT);
    }

    private boolean containsAny(String text, String... values) {
        for (String value : values) {
            if (text.contains(value)) return true;
        }
        return false;
    }

    public record FastRoute(Set<String> allowedTools, String reason) {
        public FastRoute {
            allowedTools = allowedTools == null ? Set.of() : Set.copyOf(allowedTools);
            reason = reason == null ? "" : reason;
        }
    }
}
