package com.clawbot.wechatbot.feature.excel.repository;

import com.clawbot.wechatbot.feature.excel.model.ExcelUserState;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Optional;

public interface ExcelUserStateRepository
    extends MongoRepository<ExcelUserState, String> {

    Optional<ExcelUserState> findByWechatUserId(String wechatUserId);
}
