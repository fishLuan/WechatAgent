package com.clawbot.wechatbot.feature.bilibili.repository;

import com.clawbot.wechatbot.feature.bilibili.model.BilibiliPreference;
import com.clawbot.wechatbot.feature.bilibili.model.ContentType;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.Optional;

public interface BilibiliPreferenceRepository
    extends MongoRepository<BilibiliPreference, String> {

    Optional<BilibiliPreference> findByWechatUserIdAndContentType(
        String wechatUserId, ContentType contentType);

    List<BilibiliPreference> findByContentTypeAndPushEnabledTrue(ContentType contentType);
}
