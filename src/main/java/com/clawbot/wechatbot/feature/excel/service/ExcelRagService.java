package com.clawbot.wechatbot.feature.excel.service;

import com.clawbot.wechatbot.feature.excel.model.ExcelRagKnowledge;
import com.clawbot.wechatbot.feature.excel.repository.ExcelRagKnowledgeRepository;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

import static com.clawbot.wechatbot.feature.excel.model.ExcelRagKnowledge.CATEGORY_BUSINESS_RULE;
import static com.clawbot.wechatbot.feature.excel.model.ExcelRagKnowledge.CATEGORY_FIELD_MAPPING;
import static com.clawbot.wechatbot.feature.excel.model.ExcelRagKnowledge.CATEGORY_OPERATION_EXAMPLE;

/**
 * 全局 Excel 知识库服务：启动种子、知识管理指令、列别名解析与规则命中（关键词检索版 RAG）。
 * 知识库存可复用的字段映射、业务规则、操作示例；用户表格数据仍按用户隔离，不进入知识库。
 */
@Component
public class ExcelRagService {

    private final ExcelRagKnowledgeRepository repository;

    public ExcelRagService(ExcelRagKnowledgeRepository repository) {
        this.repository = repository;
    }

    /** 启动种子：collection 为空时写入基础知识（与方案文档示例一致；每次创建新实例，避免常量对象被仓储写入 id）。 */
    @PostConstruct
    void seedIfEmpty() {
        if (repository.count() > 0) {
            return;
        }
        add(CATEGORY_FIELD_MAPPING, List.of("营收", "营业收入", "销售收入"), "营业收入", null, null);
        add(CATEGORY_FIELD_MAPPING, List.of("销售额", "销售金额"), "销售额", null, null);
        add(CATEGORY_BUSINESS_RULE, List.of("毛利", "毛利润"), null,
            "毛利润 = 营业收入 - 营业成本", null);
        add(CATEGORY_BUSINESS_RULE, List.of("库存", "预警"), null,
            "库存预警：当前库存小于安全库存时标红", null);
        add(CATEGORY_OPERATION_EXAMPLE, List.of("趋势", "折线图", "柱状图"), null, null,
            "时间序列趋势默认用折线图，分类对比默认用柱状图");
    }

    /** 添加一条知识并返回保存后的实体。 */
    public ExcelRagKnowledge add(String category, List<String> keywords, String standardField,
                                 String rule, String example) {
        return repository.save(
            new ExcelRagKnowledge(category, keywords, standardField, rule, example));
    }

    /** 最近若干条知识（最新在前）。 */
    public List<ExcelRagKnowledge> list(int limit) {
        return repository.findAllByOrderByCreatedAtDesc().stream().limit(limit).toList();
    }

    /** 知识库总条数（列表回复展示用）。 */
    public long count() {
        return repository.count();
    }

    /** 删除触发词命中该关键词的知识，返回是否删除了条目。 */
    public boolean deleteByKeyword(String keyword) {
        List<ExcelRagKnowledge> matched = repository.findByKeywordsContaining(keyword);
        if (matched.isEmpty()) {
            return false;
        }
        repository.deleteAll(matched);
        return true;
    }

    /**
     * 列别名解析：遍历字段映射知识，触发词与 term 相等或互相包含时返回其标准列名；
     * 未命中返回 null（供计划执行前把别名替换为表内真实列名）。
     */
    public String resolveColumnAlias(String term) {
        if (term == null || term.isBlank()) {
            return null;
        }
        for (ExcelRagKnowledge knowledge : repository.findAll()) {
            if (!CATEGORY_FIELD_MAPPING.equals(knowledge.getCategory())) {
                continue;
            }
            for (String keyword : knowledge.getKeywords()) {
                if (matches(keyword, term)) {
                    return knowledge.getStandardField();
                }
            }
        }
        return null;
    }

    /**
     * 规则命中：返回触发词与匹配文本命中（互相包含）的非字段映射知识（业务规则/操作示例），
     * 供成功回复前标注。
     */
    public List<ExcelRagKnowledge> findRules(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        List<ExcelRagKnowledge> hits = new ArrayList<>();
        for (ExcelRagKnowledge knowledge : repository.findAll()) {
            if (CATEGORY_FIELD_MAPPING.equals(knowledge.getCategory())) {
                continue;
            }
            for (String keyword : knowledge.getKeywords()) {
                if (matches(keyword, text)) {
                    hits.add(knowledge);
                    break;
                }
            }
        }
        return hits;
    }

    /** 包含匹配：相等或互相包含（与列定位的模糊匹配口径一致）。 */
    private static boolean matches(String a, String b) {
        return a.equals(b) || a.contains(b) || b.contains(a);
    }
}
