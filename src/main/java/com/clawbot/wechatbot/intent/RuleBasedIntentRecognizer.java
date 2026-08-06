package com.clawbot.wechatbot.intent;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class RuleBasedIntentRecognizer implements IntentRecognizer {
    private static final Pattern BILIBILI_URL = Pattern.compile(
        "(?i)((?:https?://)?(?:(?:[a-z0-9-]+\\.)?bilibili\\.com|b23\\.tv)/\\S+)");
    private static final Pattern SUBSCRIBE_INDEX = Pattern.compile(
        "^(?:订阅|追更)\\s*(?:第\\s*)?"
            + "(\\d{1,2}|[一二两三四五六七八九十]{1,3})\\s*(?:个|部)?\\s*$");
    private static final Pattern SUBSCRIBE_TITLE = Pattern.compile(
        "^(?:(?:我想|我要|我想要|帮我|请帮我)\\s*)?"
            + "(?:订阅|追更)\\s*(?:一下)?\\s*(.+?)\\s*$");
    private static final Pattern TITLE_REMINDER = Pattern.compile(
        "^(.+?)(?:更新时|有更新时|更新了)\\s*(?:提醒我|通知我)\\s*$");
    private static final Pattern SEARCH_TITLE = Pattern.compile(
        "^(?:搜索|查找|搜一下|搜|找一下|帮我找(?:一下)?)\\s*(?:B站)?\\s*(.+?)\\s*$");
    private static final Pattern MARK_TITLE = Pattern.compile(
        "^(?:我\\s*)?(?:已经|早就|刚刚|刚)?\\s*"
            + "(看过|看完了?|看了|想看|不喜欢)\\s*(?:了)?\\s*(.+?)\\s*$");
    private static final Pattern MARK_TITLE_POSTFIX = Pattern.compile(
        "^(.+?)\\s*(?:我\\s*)?(?:已经|早就)?\\s*"
            + "(看过|看完了?|看了|不喜欢)\\s*(?:了)?\\s*$");
    private static final Pattern MULTI_CONNECTOR = Pattern.compile(
        ".*(?:同时|并且|然后|以及|还要|再帮我|并帮我).*");

    @Override
    public IntentResult recognize(String userText) {
        String text = userText == null ? "" : userText.trim();
        if (text.isEmpty()) return result(IntentType.GENERAL_CHAT, 1.0);

        if (isMultiTask(text)) {
            return result(IntentType.MULTI_TASK, 0.90);
        }

        if (isWereadRequest(text)) {
            return result(IntentType.WEREAD_QUERY, 0.96);
        }

        Matcher matcher = MARK_TITLE.matcher(text);
        if (matcher.matches() && isUsableStateTitle(matcher.group(2))) {
            return markTitleResult(matcher.group(1), matcher.group(2));
        }
        matcher = MARK_TITLE_POSTFIX.matcher(text);
        if (matcher.matches() && isUsableStateTitle(matcher.group(1))) {
            return markTitleResult(matcher.group(2), matcher.group(1));
        }

        matcher = BILIBILI_URL.matcher(text);
        if (matcher.find()) {
            return result(
                IntentType.BILIBILI_SUBSCRIBE_URL,
                1.0,
                "url",
                trimTrailingPunctuation(matcher.group(1)));
        }

        matcher = SUBSCRIBE_INDEX.matcher(text);
        if (matcher.matches()) {
            return result(
                IntentType.BILIBILI_SUBSCRIBE_INDEX,
                1.0,
                "index",
                matcher.group(1));
        }

        // 查询已有订阅不是创建订阅，必须在标题订阅规则之前拦截，
        // 避免把“了哪些作品”识别成作品名。
        if (isSubscriptionListQuery(text)) {
            return result(IntentType.GENERAL_CHAT, 0.96);
        }

        matcher = SUBSCRIBE_TITLE.matcher(text);
        if (matcher.matches() && isUsableTitle(matcher.group(1))) {
            return result(
                IntentType.BILIBILI_SUBSCRIBE_TITLE,
                0.98,
                "title",
                cleanTitle(matcher.group(1)));
        }

        matcher = TITLE_REMINDER.matcher(text);
        if (matcher.matches() && isUsableTitle(matcher.group(1))) {
            return result(
                IntentType.BILIBILI_SUBSCRIBE_TITLE,
                0.94,
                "title",
                cleanTitle(matcher.group(1)));
        }

        matcher = SEARCH_TITLE.matcher(text);
        if (matcher.matches() && isUsableTitle(matcher.group(1))
            && !isWereadRequest(matcher.group(1))) {
            String title = cleanBilibiliTitle(matcher.group(1));
            if (!containsBilibiliCategory(text)) {
                return result(IntentType.CONTENT_SEARCH_AMBIGUOUS, 0.82,
                    "title", title);
            }
            return result(
                IntentType.BILIBILI_SEARCH_TITLE,
                0.98,
                "title",
                title);
        }

        if (containsBilibiliCategory(text)
            && containsAny(text, "推荐", "好看", "来点", "找点", "有啥", "想看", "看看")) {
            return new IntentResult(IntentType.BILIBILI_RECOMMEND, 0.94,
                Map.of("content_type", bilibiliContentType(text)));
        }
        if (isImageRequest(text)) {
            return result(IntentType.IMAGE_GENERATION, 0.95);
        }
        if (isDocumentRequest(text)) {
            return result(IntentType.DOCUMENT_GENERATION, 0.95);
        }
        if (containsAny(text, "天气", "汇率", "新闻", "几点", "现在时间", "身份证")) {
            return result(IntentType.TOOL_QUERY, 0.88);
        }
        return result(IntentType.GENERAL_CHAT, 0.60);
    }

    private boolean isMultiTask(String text) {
        if (!MULTI_CONNECTOR.matcher(text).matches()) return false;
        int categories = 0;
        if (containsBilibiliCategory(text) || text.contains("订阅")) categories++;
        if (isImageRequest(text)) categories++;
        if (isDocumentRequest(text)) categories++;
        if (containsAny(text, "天气", "汇率", "新闻", "现在时间")) categories++;
        return categories >= 2;
    }

    private boolean containsBilibiliCategory(String text) {
        return containsAny(text, "B站", "动漫", "动画", "番剧", "国漫", "日漫",
            "电视剧", "剧集", "美剧", "韩剧", "电影", "影片");
    }

    private boolean isWereadRequest(String text) {
        if (text == null || text.isBlank()) return false;
        if (containsAny(text, "微信读书", "书架", "阅读统计", "读了多久",
            "读书笔记", "划线", "这本书", "找书", "搜书", "推荐书",
            "推荐几本", "推书")) {
            return true;
        }
        boolean bookDomain = containsAny(text,
            "书籍", "图书", "小说", "名著", "书单", "作者", "出版社", "书");
        boolean queryAction = containsAny(text,
            "搜索", "搜一下", "查找", "找一下", "查询", "推荐", "介绍",
            "打开", "阅读", "加入书架");
        return bookDomain && queryAction;
    }

    private boolean isSubscriptionListQuery(String text) {
        return text != null && text.matches(
            "^(?:我的订阅|查看(?:我的)?订阅|列出(?:我的)?订阅|订阅列表|追更列表"
                + "|我(?:都)?订阅了(?:哪些|什么)(?:作品)?"
                + "|我订阅过(?:哪些|什么)(?:作品)?"
                + "|(?:我)?有哪些订阅"
                + "|我的追更(?:有)?哪些"
                + "|列出我订阅的作品)\\s*[？?]?$"
        );
    }

    private String bilibiliContentType(String text) {
        if (containsAny(text, "电影", "影片")) return "MOVIE";
        if (containsAny(text, "电视剧", "剧集", "美剧", "韩剧")) return "SERIES";
        return "BANGUMI";
    }

    private String cleanBilibiliTitle(String value) {
        return cleanTitle(value).replaceFirst(
            "\\s*(?:动漫|动画|番剧|电影|影片|电视剧|剧集)$", "").trim();
    }

    private boolean isImageRequest(String text) {
        return containsAny(
            text,
            "生成图片",
            "生成一张图",
            "生成一张图片",
            "画一张",
            "画个",
            "做张图");
    }

    private boolean isDocumentRequest(String text) {
        return containsAny(
            text,
            "生成文档",
            "生成word",
            "生成 Word",
            "生成pdf",
            "写成文档");
    }

    private boolean containsAny(String text, String... values) {
        for (String value : values) {
            if (text.contains(value)) return true;
        }
        return false;
    }

    private boolean isUsableTitle(String value) {
        String title = cleanTitle(value);
        return !title.isBlank()
            && !title.matches("\\d{1,2}")
            && !containsAny(title, "列表", "设置", "推荐");
    }

    private String normalizeState(String value) {
        if ("想看".equals(value)) return "want_to_watch";
        if ("不喜欢".equals(value)) return "disliked";
        return "watched";
    }

    private boolean isUsableStateTitle(String value) {
        String title = cleanTitle(value);
        if (!isUsableTitle(title)) return false;
        if (title.matches(".*(?:天气|汇率|新闻|几点|时间)$")) return false;
        return !title.matches(
            "^(?:一些|一点|点|一部|几部)?(?:动漫|番剧|电影|电视剧|剧集)$");
    }

    private IntentResult markTitleResult(String action, String title) {
        return new IntentResult(
            IntentType.BILIBILI_MARK_TITLE,
            0.98,
            Map.of(
                "state", normalizeState(action),
                "title", cleanTitle(title)));
    }

    private String cleanTitle(String value) {
        if (value == null) return "";
        return value.trim()
            .replaceAll("^[：:，,《“\\\"']+", "")
            .replaceAll("[》”\\\"'。！!？?]+$", "")
            .trim();
    }

    private String trimTrailingPunctuation(String value) {
        if (value == null) return "";
        return value.replaceAll("[，。！？；：、,.!?;:）)】》]+$", "");
    }

    private IntentResult result(IntentType type, double confidence) {
        return new IntentResult(type, confidence, Map.of());
    }

    private IntentResult result(
        IntentType type,
        double confidence,
        String slotName,
        String slotValue
    ) {
        return new IntentResult(
            type, confidence, Map.of(slotName, slotValue));
    }
}
