package com.clawbot.wechatbot.feature.excel.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 全局 Excel 知识库条目：可复用的字段映射、业务规则、操作示例（不存用户表格数据）。
 * 用户上传的工作簿数据仍按用户隔离，不进入知识库。
 */
@Document(collection = "excel_rag_knowledge")
public class ExcelRagKnowledge {

    /** 知识类别：字段映射（别名 → 标准列名）。 */
    public static final String CATEGORY_FIELD_MAPPING = "FIELD_MAPPING";
    /** 知识类别：业务规则/口径说明。 */
    public static final String CATEGORY_BUSINESS_RULE = "BUSINESS_RULE";
    /** 知识类别：操作示例（如图表选型）。 */
    public static final String CATEGORY_OPERATION_EXAMPLE = "OPERATION_EXAMPLE";
    /** 知识类别：模板。 */
    public static final String CATEGORY_TEMPLATE = "TEMPLATE";
    /** 全部合法类别，供校验器提示使用。 */
    public static final List<String> CATEGORIES = List.of(
        CATEGORY_FIELD_MAPPING, CATEGORY_BUSINESS_RULE,
        CATEGORY_OPERATION_EXAMPLE, CATEGORY_TEMPLATE);

    @Id
    private String id;
    /** 知识类别（CATEGORY_* 之一）。 */
    private String category;
    /** 触发词：与指令文本/列名做包含匹配。 */
    private List<String> keywords = new ArrayList<>();
    /** 字段映射的目标标准列名（仅 FIELD_MAPPING 使用，可空）。 */
    private String standardField;
    /** 规则/口径文本（仅 BUSINESS_RULE 使用，可空）。 */
    private String rule;
    /** 示例内容（OPERATION_EXAMPLE/TEMPLATE 使用，可空）。 */
    private String example;
    private Instant createdAt;

    public ExcelRagKnowledge() {
    }

    public ExcelRagKnowledge(String category, List<String> keywords, String standardField,
                             String rule, String example) {
        this.category = category;
        setKeywords(keywords);
        this.standardField = standardField;
        this.rule = rule;
        this.example = example;
        this.createdAt = Instant.now();
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getCategory() { return category; }
    public void setCategory(String value) { this.category = value; }
    public List<String> getKeywords() {
        if (keywords == null) keywords = new ArrayList<>();
        return keywords;
    }
    public void setKeywords(List<String> values) {
        keywords = values == null ? new ArrayList<>() : new ArrayList<>(values);
    }
    public String getStandardField() { return standardField; }
    public void setStandardField(String value) { this.standardField = value; }
    public String getRule() { return rule; }
    public void setRule(String value) { this.rule = value; }
    public String getExample() { return example; }
    public void setExample(String value) { this.example = value; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant value) { this.createdAt = value; }

    /** 类别中文说明，用于知识管理指令的回复文案。 */
    public static String labelOf(String category) {
        return switch (category) {
            case CATEGORY_FIELD_MAPPING -> "字段映射";
            case CATEGORY_BUSINESS_RULE -> "业务规则";
            case CATEGORY_OPERATION_EXAMPLE -> "操作示例";
            case CATEGORY_TEMPLATE -> "模板";
            default -> category;
        };
    }
}
