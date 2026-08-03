package com.clawbot.wechatbot.feature.bilibili.repository;

import com.clawbot.wechatbot.feature.bilibili.model.BilibiliCrawlState;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface BilibiliCrawlStateRepository
    extends MongoRepository<BilibiliCrawlState, String> {
}
