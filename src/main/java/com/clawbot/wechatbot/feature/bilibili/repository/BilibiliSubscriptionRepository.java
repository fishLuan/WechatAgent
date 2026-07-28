package com.clawbot.wechatbot.feature.bilibili.repository;

import com.clawbot.wechatbot.feature.bilibili.model.BilibiliSubscription;
import com.clawbot.wechatbot.feature.bilibili.model.SubscriptionStatus;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.Optional;

public interface BilibiliSubscriptionRepository
    extends MongoRepository<BilibiliSubscription, String> {

    Optional<BilibiliSubscription> findByWechatUserIdAndSeasonId(
        String wechatUserId, String seasonId);

    List<BilibiliSubscription> findByWechatUserIdAndStatus(
        String wechatUserId, SubscriptionStatus status);

    List<BilibiliSubscription> findByStatus(SubscriptionStatus status);
}
