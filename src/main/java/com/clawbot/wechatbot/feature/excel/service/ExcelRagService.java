package com.clawbot.wechatbot.feature.excel.service;

import com.clawbot.wechatbot.feature.excel.model.ExcelRagKnowledge;
import com.clawbot.wechatbot.feature.excel.repository.ExcelRagKnowledgeRepository;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
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

    /** 添加知识结果：保存后的实体 + 是否更新了既有条目。 */
    public record AddResult(ExcelRagKnowledge knowledge, boolean updated) {
    }

    /**
     * 添加或更新知识：同类别下首个触发词已存在时更新该条目（避免重复堆积），否则新增。
     * 更新时以新内容整体替换，并保留原创建时间。
     */
    public AddResult upsert(String category, List<String> keywords, String standardField,
                            String rule, String example) {
        if (keywords == null || keywords.isEmpty()) {
            throw new IllegalArgumentException("知识触发词不能为空");
        }
        String first = keywords.get(0);
        for (ExcelRagKnowledge existing : repository.findAll()) {
            if (!category.equals(existing.getCategory())) {
                continue;
            }
            if (existing.getKeywords().contains(first)) {
                existing.setKeywords(keywords);
                existing.setStandardField(standardField);
                existing.setRule(rule);
                existing.setExample(example);
                return new AddResult(repository.save(existing), true);
            }
        }
        return new AddResult(repository.save(
            new ExcelRagKnowledge(category, keywords, standardField, rule, example)), false);
    }

    /** 最近若干条知识（最新在前）。 */
    public List<ExcelRagKnowledge> list(int limit) {
        return repository.findAllByOrderByCreatedAtDesc().stream().limit(limit).toList();
    }

    /** 知识库总条数（列表回复展示用）。 */
    public long count() {
        return repository.count();
    }

    /**
     * 删除触发词**完全等于** keyword 的知识（精确匹配，避免"销售"误删"销售额"条目）；
     * 返回是否删除了条目。
     */
    public boolean deleteByKeyword(String keyword) {
        List<ExcelRagKnowledge> exact = repository.findByKeywordsContaining(keyword).stream()
            .filter(knowledge -> knowledge.getKeywords().contains(keyword))
            .toList();
        if (exact.isEmpty()) {
            return false;
        }
        repository.deleteAll(exact);
        return true;
    }

    /**
     * 列别名解析：精确匹配优先；互相包含时取**最短**触发词（"销售"命中"销售额"而非"销售收入"）；
     * 未命中返回 null（供计划执行前把别名替换为表内真实列名）。
     */
    public String resolveColumnAlias(String term) {
        if (term == null || term.isBlank()) {
            return null;
        }
        String best = null;
        int bestScore = Integer.MAX_VALUE;
        for (ExcelRagKnowledge knowledge : repository.findAll()) {
            if (!CATEGORY_FIELD_MAPPING.equals(knowledge.getCategory())) {
                continue;
            }
            String match = bestKeywordMatch(knowledge.getKeywords(), term);
            if (match == null) {
                continue;
            }
            int score = match.equals(term) ? 0 : match.length();
            if (score < bestScore) {
                bestScore = score;
                best = knowledge.getStandardField();
            }
        }
        return best;
    }

    /**
     * 规则命中：返回触发词与匹配文本命中的非字段映射知识（业务规则/操作示例），
     * 精确命中优先、其次按触发词长度排序，供成功回复前标注。
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
            if (bestKeywordMatch(knowledge.getKeywords(), text) != null) {
                hits.add(knowledge);
            }
        }
        // 精确命中（触发词=文本）排在包含命中之前；同级别保持原顺序
        hits.sort(Comparator.comparingInt(
            knowledge -> bestKeywordMatch(knowledge.getKeywords(), text).equals(text) ? 0 : 1));
        return hits;
    }

    /** 取最佳命中触发词：精确匹配优先，其次互相包含中较短者（"销售"倾向"销售额"）；无命中返回 null。 */
    private static String bestKeywordMatch(List<String> keywords, String term) {
        String best = null;
        for (String keyword : keywords) {
            if (keyword.equals(term)) {
                return keyword; // 精确最优
            }
            if ((keyword.contains(term) || term.contains(keyword))
                && (best == null || keyword.length() < best.length())) {
                best = keyword;
            }
        }
        return best;
    }
}
