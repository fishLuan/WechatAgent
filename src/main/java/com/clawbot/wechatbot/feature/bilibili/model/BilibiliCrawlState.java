package com.clawbot.wechatbot.feature.bilibili.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/** Persistent cursor used to avoid restarting every candidate crawl from page one. */
@Document(collection = "bilibili_crawl_state")
public class BilibiliCrawlState {
    @Id
    private String contentType;
    private int nextPage = 1;
    private Instant updatedAt;

    public BilibiliCrawlState() {
    }

    public BilibiliCrawlState(ContentType contentType, int nextPage) {
        this.contentType = contentType.name();
        this.nextPage = Math.max(1, nextPage);
        this.updatedAt = Instant.now();
    }

    public String getContentType() { return contentType; }
    public void setContentType(String value) { this.contentType = value; }
    public int getNextPage() { return Math.max(1, nextPage); }
    public void setNextPage(int value) { this.nextPage = Math.max(1, value); }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant value) { this.updatedAt = value; }
}
