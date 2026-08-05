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

    List<BilibiliContent> findTop20ByTitleContainingIgnoreCaseOrderByRatingDesc(
        String title);

    List<BilibiliContent> findByContentTypeAndRatingGreaterThanEqualOrderByRatingDesc(
        ContentType contentType, double minimumRating);

    @Query(value = "{ 'contentType': ?0, 'latestEpisodePubTime': { $gte: ?1, $lt: ?2 } }",
           sort = "{ 'latestEpisodePubTime': -1 }")
    List<BilibiliContent> findUpdatesBetween(
        ContentType contentType, Instant fromInclusive, Instant toExclusive);

    List<BilibiliContent> findByContentTypeAndFinishedFalseAndLatestEpisodeNumberGreaterThanOrderByRatingDesc(
        ContentType contentType, int minEpisodeNumber);
}
