package com.clawbot.wechatbot.service.agent.acceptance;

import com.clawbot.wechatbot.service.agent.AcceptanceCriterion;
import com.clawbot.wechatbot.service.agent.AcceptanceOperator;
import com.clawbot.wechatbot.service.agent.AgentTask;
import com.clawbot.wechatbot.service.agent.AgentTaskResult;
import com.clawbot.wechatbot.service.agent.AgentTaskType;
import com.clawbot.wechatbot.service.agent.AgentAttachment;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultTaskAcceptanceEvaluatorTests {
    private final ObjectMapper mapper = new ObjectMapper();
    private final DefaultTaskAcceptanceEvaluator evaluator =
        new DefaultTaskAcceptanceEvaluator(mapper);

    @Test
    void passesStructuredResultThatMeetsSchemaAndCriteria() {
        AgentTask task = task(
            mapper.createObjectNode()
                .put("city", "string")
                .put("temperature", "number"),
            List.of(
                criterion("$.city", AcceptanceOperator.EQUALS, mapper.valueToTree("杭州")),
                criterion("$.temperature", AcceptanceOperator.GREATER_THAN,
                    mapper.valueToTree(0))));
        AgentTaskResult result = AgentTaskResult.success(
            task, "{\"city\":\"杭州\",\"temperature\":25}", List.of());

        TaskEvaluation evaluation = evaluator.evaluate(task, result, Map.of());

        assertTrue(evaluation.passed());
        assertEquals("杭州", evaluation.verifiedOutput().path("city").asText());
    }

    @Test
    void requestsReplanWhenRequiredOutputDoesNotMatch() {
        AgentTask task = task(
            mapper.createObjectNode().put("city", "string"),
            List.of(criterion(
                "$.city", AcceptanceOperator.EQUALS, mapper.valueToTree("杭州"))));
        AgentTaskResult result = AgentTaskResult.success(
            task, "{\"city\":\"上海\"}", List.of());

        TaskEvaluation evaluation = evaluator.evaluate(task, result, Map.of());

        assertEquals(TaskDecision.REPLAN, evaluation.decision());
        assertEquals("TASK_ACCEPTANCE_FAILED", evaluation.code());
        assertFalse(evaluation.failedCriteria().isEmpty());
    }

    @Test
    void classifiesMissingPlannerFieldsAsSchemaMismatch() {
        AgentTask task = task(
            mapper.createObjectNode().put("weather_info", "string"), List.of());

        TaskEvaluation evaluation = evaluator.evaluate(task,
            AgentTaskResult.success(task, "阜阳今天晴", List.of()), Map.of());

        assertEquals(TaskDecision.REPLAN, evaluation.decision());
        assertEquals("TASK_OUTPUT_SCHEMA_MISMATCH", evaluation.code());
    }

    @Test
    void wrapsPlainTextSoTextCriteriaCanBeEvaluated() {
        AgentTask task = task(
            mapper.createObjectNode().put("text", "string"),
            List.of(criterion(
                "$.text", AcceptanceOperator.CONTAINS, mapper.valueToTree("完成"))));

        TaskEvaluation evaluation = evaluator.evaluate(
            task,
            AgentTaskResult.success(task, "任务已经完成", List.of()),
            Map.of());

        assertTrue(evaluation.passed());
    }

    @Test
    void returnsRetryForHandlerFailure() {
        AgentTask task = task(mapper.createObjectNode(), List.of());

        TaskEvaluation evaluation = evaluator.evaluate(
            task, AgentTaskResult.failure(task, "模拟失败"), Map.of());

        assertEquals(TaskDecision.RETRY, evaluation.decision());
        assertEquals("TASK_EXECUTION_FAILED", evaluation.code());
    }

    @Test
    void optionalCriterionDoesNotRejectResult() {
        AcceptanceCriterion optional = new AcceptanceCriterion(
            "可选评分", "$.rating", AcceptanceOperator.GREATER_THAN,
            mapper.valueToTree(9), false);
        AgentTask task = task(mapper.createObjectNode(), List.of(optional));

        TaskEvaluation evaluation = evaluator.evaluate(
            task, AgentTaskResult.success(task, "{\"title\":\"作品\"}", List.of()),
            Map.of());

        assertTrue(evaluation.passed());
    }

    @Test
    void imageAttachmentPassesEvenWhenPlannerIncorrectlyRequiresImageUrl() {
        AgentTask task = new AgentTask(
            "image-1", 0, AgentTaskType.IMAGE_GENERATION, "", "生成图片",
            mapper.createObjectNode(),
            mapper.createObjectNode().put("image_url", "string"),
            List.of(criterion("$.image_url", AcceptanceOperator.EXISTS,
                mapper.nullNode())), List.of());
        AgentAttachment image = new AgentAttachment(
            AgentAttachment.AttachmentType.IMAGE,
            new byte[] {1, 2, 3}, "image.png", "生成图片");

        TaskEvaluation evaluation = evaluator.evaluate(task,
            AgentTaskResult.success(task, "图片已生成", List.of(image)), Map.of());

        assertTrue(evaluation.passed());
        assertEquals("IMAGE",
            evaluation.verifiedOutput().path("attachments").path(0)
                .path("type").asText());
    }

    @Test
    void imageTaskWithoutImageAttachmentRequestsRetry() {
        AgentTask task = new AgentTask(
            "image-1", 0, AgentTaskType.IMAGE_GENERATION, "", "生成图片",
            List.of());

        TaskEvaluation evaluation = evaluator.evaluate(task,
            AgentTaskResult.success(task, "图片已生成", List.of()), Map.of());

        assertEquals(TaskDecision.RETRY, evaluation.decision());
        assertEquals("IMAGE_ATTACHMENT_MISSING", evaluation.code());
    }

    private AcceptanceCriterion criterion(
        String path, AcceptanceOperator operator, com.fasterxml.jackson.databind.JsonNode expected
    ) {
        return new AcceptanceCriterion("条件：" + path, path, operator, expected, true);
    }

    private AgentTask task(
        ObjectNode expectedOutput, List<AcceptanceCriterion> criteria
    ) {
        return new AgentTask(
            "task-1", 0, AgentTaskType.CHAT_TOOL, "", "测试任务",
            mapper.createObjectNode(), expectedOutput, criteria, List.of());
    }
}
