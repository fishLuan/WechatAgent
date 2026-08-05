package com.clawbot.wechatbot.feature.voice;

import org.springframework.data.mongodb.repository.MongoRepository;

public interface VoiceReplyContextRepository
    extends MongoRepository<VoiceReplyContext, String> {
}
