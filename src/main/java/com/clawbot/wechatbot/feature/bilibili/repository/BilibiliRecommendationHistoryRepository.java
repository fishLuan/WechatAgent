package com.clawbot.wechatbot.feature.bilibili.repository;

import com.clawbot.wechatbot.feature.bilibili.model.BilibiliRecommendationHistory;
import com.clawbot.wechatbot.feature.bilibili.model.ContentType;
import com.clawbot.wechatbot.feature.bilibili.model.RecommendationState;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface BilibiliRecommendationHistoryRepository
    extends MongoRepository<BilibiliRecommendationHistory, String> {

    Optional<BilibiliRecommendationHistory> findByWechatUserIdAndContentTypeAndContentId(
        String wechatUserId, ContentType contentType, String contentId);

    boolean existsByWechatUserIdAndContentTypeAndContentId(
        String wechatUserId, ContentType contentType, String contentId);

    List<BilibiliRecommendationHistory>
        findByWechatUserIdAndContentTypeAndStateIn(
            String wechatUserId,
            ContentType contentType,
            Collection<RecommendationState> states);
}
