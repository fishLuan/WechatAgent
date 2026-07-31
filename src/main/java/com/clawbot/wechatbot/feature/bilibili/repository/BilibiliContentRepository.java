package com.clawbot.wechatbot.feature.bilibili.repository;

import com.clawbot.wechatbot.feature.bilibili.model.BilibiliContent;
import com.clawbot.wechatbot.feature.bilibili.model.ContentType;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface BilibiliContentRepository
    extends MongoRepository<BilibiliContent, String> {

    Optional<BilibiliContent> findByContentTypeAndContentId(
        ContentType contentType, String contentId);

    List<BilibiliContent> findByContentTypeAndRatingGreaterThanEqualOrderByRatingDesc(
        ContentType contentType, double minimumRating);

    @Query(value = "{ 'contentType': ?0, 'latestEpisodePubTime': { $gt: ?1 } }",
           sort = "{ 'latestEpisodePubTime': -1 }")
    List<BilibiliContent> findTodayUpdates(ContentType contentType, Instant since);

    List<BilibiliContent> findByContentTypeAndFinishedFalseAndLatestEpisodeNumberGreaterThanOrderByRatingDesc(
        ContentType contentType, int minEpisodeNumber);
}
