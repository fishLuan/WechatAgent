package com.clawbot.wechatbot.service.agent.contract;

import java.util.List;
import java.util.Map;

/** Canonical structured contract shared by news producers and tabular consumers. */
public final class NewsDataContract {
    public static final String ITEMS = "items";
    public static final List<String> ITEM_FIELDS = List.of(
        "title", "description", "source", "publish_time", "url");
    public static final Map<String, String> DISPLAY_HEADERS = Map.of(
        "title", "标题",
        "description", "摘要",
        "source", "来源",
        "publish_time", "发布时间",
        "url", "链接");

    private NewsDataContract() {
    }
}
