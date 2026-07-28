package com.clawbot.wechatbot.feature.bilibili.support;

import com.clawbot.wechatbot.feature.bilibili.model.BilibiliContent;
import com.clawbot.wechatbot.feature.bilibili.model.BilibiliPreference;
import com.clawbot.wechatbot.feature.bilibili.model.BilibiliRecommendationHistory;
import com.clawbot.wechatbot.feature.bilibili.model.BilibiliSubscription;
import com.clawbot.wechatbot.feature.bilibili.model.BilibiliUpdateEvent;
import jakarta.annotation.PostConstruct;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.Sort.Direction;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.stereotype.Component;

/** 功能启用时显式创建公共集合所需的唯一索引和调度查询索引。 */
@Component
@ConditionalOnProperty(
    name = "clawbot.bilibili.enabled",
    havingValue = "true"
)
public class BilibiliMongoIndexInitializer {
    private final MongoTemplate mongoTemplate;

    public BilibiliMongoIndexInitializer(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    @PostConstruct
    void initialize() {
        mongoTemplate.indexOps(BilibiliContent.class).createIndex(
            new Index()
                .on("contentType", Direction.ASC)
                .on("contentId", Direction.ASC)
                .unique()
                .named("uk_bilibili_content_type_content"));

        mongoTemplate.indexOps(BilibiliSubscription.class).createIndex(
            new Index()
                .on("wechatUserId", Direction.ASC)
                .on("seasonId", Direction.ASC)
                .unique()
                .named("uk_bilibili_subscription_user_season"));
        mongoTemplate.indexOps(BilibiliSubscription.class).createIndex(
            new Index()
                .on("status", Direction.ASC)
                .on("lastCheckedAt", Direction.ASC)
                .named("idx_bilibili_subscription_check"));

        mongoTemplate.indexOps(BilibiliUpdateEvent.class).createIndex(
            new Index()
                .on("subscriptionId", Direction.ASC)
                .on("episodeId", Direction.ASC)
                .unique()
                .named("uk_bilibili_update_subscription_episode"));
        mongoTemplate.indexOps(BilibiliUpdateEvent.class).createIndex(
            new Index()
                .on("status", Direction.ASC)
                .on("detectedAt", Direction.ASC)
                .named("idx_bilibili_update_delivery"));

        mongoTemplate.indexOps(BilibiliPreference.class).createIndex(
            new Index()
                .on("wechatUserId", Direction.ASC)
                .on("contentType", Direction.ASC)
                .unique()
                .named("uk_bilibili_preference_user_type"));

        mongoTemplate.indexOps(BilibiliRecommendationHistory.class).createIndex(
            new Index()
                .on("wechatUserId", Direction.ASC)
                .on("contentType", Direction.ASC)
                .on("contentId", Direction.ASC)
                .unique()
                .named("uk_bilibili_history_user_type_content"));
    }
}
