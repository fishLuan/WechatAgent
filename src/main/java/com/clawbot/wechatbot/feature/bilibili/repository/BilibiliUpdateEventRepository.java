package com.clawbot.wechatbot.feature.bilibili.repository;

import com.clawbot.wechatbot.feature.bilibili.model.BilibiliUpdateEvent;
import com.clawbot.wechatbot.feature.bilibili.model.UpdateEventStatus;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

public interface BilibiliUpdateEventRepository
    extends MongoRepository<BilibiliUpdateEvent, String> {

    boolean existsBySubscriptionIdAndEpisodeId(String subscriptionId, String episodeId);

    List<BilibiliUpdateEvent> findByStatusOrderByDetectedAtAsc(UpdateEventStatus status);
}
