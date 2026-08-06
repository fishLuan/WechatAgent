package com.clawbot.wechatbot.feature.excel.repository;

import com.clawbot.wechatbot.feature.excel.model.ExcelRagKnowledge;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

/** 全局 Excel 知识库仓储：按触发词（数组包含匹配）查询、按创建时间倒序。 */
public interface ExcelRagKnowledgeRepository
    extends MongoRepository<ExcelRagKnowledge, String> {

    /** 命中触发词的知识：keywords 数组包含 keyword（Mongo 数组包含匹配）的条目。 */
    List<ExcelRagKnowledge> findByKeywordsContaining(String keyword);

    /** 全部知识按创建时间倒序（最新在前），供列表展示。 */
    List<ExcelRagKnowledge> findAllByOrderByCreatedAtDesc();
}
