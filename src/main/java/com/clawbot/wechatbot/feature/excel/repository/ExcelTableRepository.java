package com.clawbot.wechatbot.feature.excel.repository;

import com.clawbot.wechatbot.feature.excel.model.ExcelTable;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Optional;

public interface ExcelTableRepository
    extends MongoRepository<ExcelTable, String> {

    Optional<ExcelTable> findByWechatUserId(String wechatUserId);
}
