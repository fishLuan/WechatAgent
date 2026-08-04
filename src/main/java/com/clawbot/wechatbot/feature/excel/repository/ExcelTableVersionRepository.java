package com.clawbot.wechatbot.feature.excel.repository;

import com.clawbot.wechatbot.feature.excel.model.ExcelTableVersion;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

public interface ExcelTableVersionRepository
    extends MongoRepository<ExcelTableVersion, String> {

    /** 某表全部版本，按创建时间倒序（最新在前）。 */
    List<ExcelTableVersion> findByTableIdOrderByCreatedAtDesc(String tableId);

    /** 某表版本数量。 */
    long countByTableId(String tableId);
}
