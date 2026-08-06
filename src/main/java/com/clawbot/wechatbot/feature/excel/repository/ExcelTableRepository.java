package com.clawbot.wechatbot.feature.excel.repository;

import com.clawbot.wechatbot.feature.excel.model.ExcelTable;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

public interface ExcelTableRepository
    extends MongoRepository<ExcelTable, String> {

    /** 某用户全部表格（多工作簿：一个用户可有多张表）。 */
    List<ExcelTable> findByWechatUserId(String wechatUserId);
}
