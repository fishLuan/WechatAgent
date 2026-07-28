package com.clawbot.wechatbot.feature.bilibili.model;

/** B 站统一内容类型，决定候选池、偏好和追更规则。 */
public enum ContentType {
    BANGUMI(true),
    SERIES(true),
    MOVIE(false),
    UPLOADER(false);

    private final boolean episodeTrackable;

    ContentType(boolean episodeTrackable) {
        this.episodeTrackable = episodeTrackable;
    }

    public boolean isEpisodeTrackable() {
        return episodeTrackable;
    }
}
