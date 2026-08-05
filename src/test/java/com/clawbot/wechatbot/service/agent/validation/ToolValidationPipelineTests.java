package com.clawbot.wechatbot.service.agent.validation;

import com.clawbot.wechatbot.tools.ToolExecutionOutcome;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolValidationPipelineTests {
    private final ObjectMapper mapper = new ObjectMapper();
    private final ToolValidationPipeline pipeline =
        new ToolValidationPipeline(mapper, List.of(), 0.6D);

    @Test
    void acceptsResultThatMatchesToolArguments() throws Exception {
        ToolExecutionOutcome outcome = success(
            "{\"success\":true,\"query_city\":\"杭州市\",\"lives\":[{\"weather\":\"晴\"}]}");

        var validated = pipeline.validate(
            "查询杭州天气", "get_weather", "{\"city\":\"杭州\"}",
            outcome, Map.of());

        assertTrue(validated.validation().passed());
        assertTrue(validated.outcome().success());
    }

    @Test
    void rejectsResultWhoseIdentityConflictsWithArguments() throws Exception {
        ToolExecutionOutcome outcome = success(
            "{\"success\":true,\"query_city\":\"上海\",\"lives\":[{\"weather\":\"晴\"}]}");

        var validated = pipeline.validate(
            "查询杭州天气并生成出行计划", "get_weather",
            "{\"city\":\"杭州\"}", outcome, Map.of());

        assertEquals(ToolValidationAction.REPLAN, validated.validation().action());
        assertFalse(validated.outcome().success());
        assertTrue(validated.outcome().content().contains("discarded_untrusted_result"));
        assertFalse(validated.outcome().content().contains("上海"));
    }

    @Test
    void rejectsSuccessEnvelopeWithoutBusinessPayload() throws Exception {
        var validated = pipeline.validate(
            "查询数据", "sample_tool", "{}", success("{\"success\":true}"), Map.of());

        assertEquals(
            "MISSING_BUSINESS_PAYLOAD", validated.validation().code());
        assertFalse(validated.outcome().success());
    }

    @Test
    void specializedValidatorCanRejectSemanticallyWrongResult() throws Exception {
        ToolResultValidator domainValidator = new ToolResultValidator() {
            @Override
            public boolean supports(String toolName) {
                return "search_work".equals(toolName);
            }

            @Override
            public int order() {
                return 10;
            }

            @Override
            public ToolValidationResult validate(ToolValidationContext context) {
                return new ToolValidationResult(
                    ToolValidationAction.REPLAN, 1D, "WORK_NOT_RELEVANT",
                    "搜索结果与用户指定作品不相关", "更换关键词并重新搜索");
            }
        };
        ToolValidationPipeline customPipeline =
            new ToolValidationPipeline(mapper, List.of(domainValidator), 0.6D);

        var validated = customPipeline.validate(
            "搜索并订阅火影忍者", "search_work", "{\"keyword\":\"火影忍者\"}",
            success("{\"success\":true,\"items\":[{\"title\":\"海贼王\"}]}"), Map.of());

        assertEquals("WORK_NOT_RELEVANT", validated.validation().code());
        assertFalse(validated.outcome().success());
    }

    private ToolExecutionOutcome success(String content) {
        return new ToolExecutionOutcome(content, true, false, "");
    }
}
