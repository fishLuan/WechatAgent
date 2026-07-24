package com.clawbot.wechatbot.tools.bazitool;

import com.clawbot.wechatbot.tools.FunctionTool;
import com.clawbot.wechatbot.tools.bazitool.model.BaziChart;
import com.clawbot.wechatbot.tools.bazitool.model.BaziRequest;
import com.clawbot.wechatbot.tools.bazitool.model.FortuneAnalysis;
import com.clawbot.wechatbot.tools.bazitool.model.PillarDetails;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;
import java.util.Map;

/** Function-calling tool for traditional BaZi calculation and annual analysis. */
public final class BaziFortuneTool implements FunctionTool {
    private final ObjectMapper mapper;
    private final BaziCalculator calculator;
    private final BaziFortuneAnalyzer analyzer;

    public BaziFortuneTool(ObjectMapper mapper) {
        this.mapper = mapper;
        this.calculator = new BaziCalculator();
        this.analyzer = new BaziFortuneAnalyzer();
    }

    @Override
    public String name() {
        return "calculate_bazi_fortune";
    }

    @Override
    public JsonNode definition() {
        ObjectNode function = mapper.createObjectNode();
        function.put("name", name());
        function.put("description",
            "根据用户主动提供的东八区出生日期和时间计算传统四柱八字、五行、十神、纳音及指定年份流年关系。"
                + "仅供传统文化和娱乐参考，不可用于医疗、投资、婚姻等重大决策。");

        ObjectNode parameters = function.putObject("parameters");
        parameters.put("type", "object");
        parameters.put("additionalProperties", false);
        ObjectNode properties = parameters.putObject("properties");
        properties.putObject("birth_date")
            .put("type", "string")
            .put("description", "出生日期，格式 yyyy-MM-dd，例如 2001-08-15");
        properties.putObject("birth_time")
            .put("type", "string")
            .put("description", "出生时间，24小时制 HH:mm 或 HH:mm:ss，例如 14:30");
        ObjectNode calendarType = properties.putObject("calendar_type");
        calendarType.put("type", "string");
        calendarType.putArray("enum").add("SOLAR").add("LUNAR");
        calendarType.put("description", "日期类型：SOLAR 为公历，LUNAR 为农历，默认 SOLAR");
        properties.putObject("leap_month")
            .put("type", "boolean")
            .put("description", "农历日期是否为闰月，默认 false；仅 calendar_type=LUNAR 时有效");
        properties.putObject("timezone")
            .put("type", "string")
            .put("description", "第一版仅支持 Asia/Shanghai、GMT+8、UTC+8 或 +08:00");
        properties.putObject("fortune_year")
            .put("type", "integer")
            .put("minimum", 1900)
            .put("maximum", 2100)
            .put("description", "需要分析的流年年份；省略时使用当前年份");
        parameters.putArray("required").add("birth_date").add("birth_time");

        ObjectNode tool = mapper.createObjectNode();
        tool.put("type", "function");
        tool.set("function", function);
        return tool;
    }

    @Override
    public String execute(JsonNode arguments) throws Exception {
        try {
            BaziRequest request = BaziInputValidator.parse(arguments);
            BaziChart chart = calculator.calculate(request);
            FortuneAnalysis fortune = analyzer.analyze(chart, request.fortuneYear());
            return mapper.writeValueAsString(successResult(request, chart, fortune));
        } catch (IllegalArgumentException e) {
            ObjectNode result = mapper.createObjectNode();
            result.put("success", false);
            result.put("error", e.getMessage());
            return mapper.writeValueAsString(result);
        }
    }

    private ObjectNode successResult(BaziRequest request, BaziChart chart,
                                     FortuneAnalysis fortune) {
        ObjectNode root = mapper.createObjectNode();
        root.put("success", true);
        ObjectNode input = root.putObject("input");
        input.put("calendar_type", request.calendarType().name());
        input.put("leap_month", request.leapMonth());
        input.put("timezone", "Asia/Shanghai");
        input.put("birth_date", String.format("%04d-%02d-%02d",
            request.birthYear(), request.birthMonth(), request.birthDay()));
        input.put("birth_time", request.birthTime().toString());

        root.put("solar_datetime", chart.solarDateTime());
        root.put("lunar_datetime", chart.lunarDateTime());
        root.put("zodiac", chart.zodiac());
        root.put("day_master", chart.dayMaster());

        ArrayNode pillars = root.putArray("four_pillars");
        String[] labels = {"年柱", "月柱", "日柱", "时柱"};
        for (int index = 0; index < chart.pillars().size(); index++) {
            PillarDetails pillar = chart.pillars().get(index);
            ObjectNode item = pillars.addObject();
            item.put("label", labels[index]);
            item.put("pillar", pillar.pillar());
            item.put("stem", pillar.stem());
            item.put("branch", pillar.branch());
            item.put("five_elements", pillar.fiveElements());
            item.put("na_yin", pillar.naYin());
            item.put("stem_ten_god", pillar.stemTenGod());
            addStrings(item.putArray("branch_ten_gods"), pillar.branchTenGods());
        }

        ObjectNode counts = root.putObject("visible_five_element_counts");
        for (Map.Entry<String, Integer> entry : chart.visibleFiveElementCounts().entrySet()) {
            counts.put(entry.getKey(), entry.getValue());
        }

        ObjectNode annual = root.putObject("fortune_year");
        annual.put("year", fortune.year());
        annual.put("pillar", fortune.pillar());
        annual.put("stem_element", fortune.stemElement());
        annual.put("branch_element", fortune.branchElement());
        addStrings(annual.putArray("relations"), fortune.relations());
        addStrings(annual.putArray("interpretation_hints"), fortune.interpretationHints());
        root.put("notice", "以上内容依据传统干支规则生成，仅供文化娱乐参考，不代表确定预测。");
        return root;
    }

    private void addStrings(ArrayNode array, List<String> values) {
        values.forEach(array::add);
    }
}
