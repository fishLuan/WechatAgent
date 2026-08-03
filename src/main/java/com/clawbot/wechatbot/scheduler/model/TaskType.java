package com.clawbot.wechatbot.scheduler.model;

public enum TaskType {
    SIMPLE_TEXT,
    ONE_TIME_REMINDER,
    /** B站推荐推送（动漫/剧集/电影），paramsJson 支持 content_type + count */
    BILIBILI_PUSH,
}
