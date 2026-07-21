package com.luanxv.pre.wechatAI.repository;

import com.luanxv.pre.wechatAI.model.ChatMemory;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

public interface ChatMemoryRepository extends MongoRepository<ChatMemory, String> {
    List<ChatMemory> findByUserIdOrderByCreatedAtDesc(String userId, Pageable pageable);
}
