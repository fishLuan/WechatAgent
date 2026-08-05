package com.clawbot.wechatbot.feature.document.repository;

import com.clawbot.wechatbot.feature.document.model.WordDocumentSession;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.Optional;

public interface WordDocumentSessionRepository
    extends MongoRepository<WordDocumentSession, String> {

    Optional<WordDocumentSession> findFirstByWechatUserIdAndActiveTrueOrderByUpdatedAtDesc(
        String wechatUserId);

    List<WordDocumentSession> findByWechatUserIdAndActiveTrueOrderByUpdatedAtDesc(
        String wechatUserId);
}
