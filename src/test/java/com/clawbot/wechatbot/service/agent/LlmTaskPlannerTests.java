package com.clawbot.wechatbot.service.agent;

import com.clawbot.wechatbot.service.client.DeepSeekClient;
import com.clawbot.wechatbot.skills.SkillCatalog;
import com.clawbot.wechatbot.skills.SkillDefinition;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LlmTaskPlannerTests {

    @Test
    void parsesTypedTasksAndCanonicalizesDependencies() throws Exception {
        LlmTaskPlanner planner = planner(5);

        List<AgentTask> tasks = planner.parseTasks(
            "根据杭州天气生成西湖图片",
            """
                ```json
                {"tasks":[
                  {"id":"weather","type":"CHAT_TOOL","instruction":"查询杭州今天的天气","depends_on":[]},
                  {"id":"image","type":"IMAGE_GENERATION","instruction":"生成一张西湖图片","depends_on":["weather"]}
                ]}
                ```
                """);

        assertEquals(2, tasks.size());
        assertEquals(AgentTaskType.CHAT_TOOL, tasks.get(0).type());
        assertEquals(AgentTaskType.IMAGE_GENERATION, tasks.get(1).type());
        assertEquals(List.of("task-1"), tasks.get(1).dependencies());
    }

    @Test
    void parsesStructuredInputExpectedOutputAndAcceptanceCriteria() throws Exception {
        LlmTaskPlanner planner = planner(5);

        List<AgentTask> tasks = planner.parseTasks(
            "查询杭州明天天气",
            """
                {"tasks":[{
                  "id":"weather",
                  "type":"CHAT_TOOL",
                  "instruction":"查询杭州明天天气",
                  "input":{"city":"杭州","date":"2026-08-05"},
                  "expected_output":{"city":"string","date":"string","weather":"string"},
                  "acceptance_criteria":[
                    {"description":"城市必须一致","path":"$.city","operator":"EQUALS","expected":"杭州","required":true},
                    {"description":"必须返回天气","path":"$.weather","operator":"NOT_EMPTY","required":true}
                  ],
                  "depends_on":[]
                }]}
                """);

        AgentTask task = tasks.get(0);
        assertEquals("杭州", task.input().path("city").asText());
        assertEquals("string", task.expectedOutput().path("weather").asText());
        assertEquals(2, task.acceptanceCriteria().size());
        assertEquals(
            AcceptanceOperator.EQUALS,
            task.acceptanceCriteria().get(0).operator());
        assertEquals(
            "杭州",
            task.acceptanceCriteria().get(0).expectedValue().asText());
    }

    @Test
    void canonicalizesTaskIdsInsideResultReferences() throws Exception {
        LlmTaskPlanner planner = planner(5);

        List<AgentTask> tasks = planner.parseTasks(
            "搜索后订阅",
            """
                {"tasks":[
                  {"id":"search","type":"CHAT_TOOL","instruction":"搜索作品",
                   "input":{},"expected_output":{"selected":"object"},
                   "acceptance_criteria":[],"depends_on":[]},
                  {"id":"subscribe","type":"CHAT_TOOL","instruction":"订阅作品",
                   "input":{"seasonId":{"$ref":"search.output.selected.seasonId"}},
                   "expected_output":{},"acceptance_criteria":[],"depends_on":["search"]}
                ]}
                """);

        assertEquals(
            "task-1.output.selected.seasonId",
            tasks.get(1).input().path("seasonId").path("$ref").asText());
        assertEquals(List.of("task-1"), tasks.get(1).dependencies());
    }

    @Test
    void keepsStructuredTaskJsonImmutable() {
        ObjectNode input = new com.fasterxml.jackson.databind.ObjectMapper()
            .createObjectNode().put("city", "杭州");
        AgentTask task = new AgentTask(
            "task-1", 0, AgentTaskType.CHAT_TOOL, "", "查询天气",
            input,
            new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode(),
            List.of(), List.of());

        input.put("city", "上海");
        ObjectNode exposed = (ObjectNode) task.input();
        exposed.put("city", "北京");

        assertEquals("杭州", task.input().path("city").asText());
    }

    @Test
    void ignoresInvalidAcceptanceCriterionWithoutDiscardingTask() throws Exception {
        LlmTaskPlanner planner = planner(5);

        AgentTask task = planner.parseTasks(
            "查询天气",
            """
                {"tasks":[{"id":"t1","type":"CHAT_TOOL","instruction":"查询天气",
                "input":{},"expected_output":{},
                "acceptance_criteria":[
                  {"path":"city","operator":"UNKNOWN","required":true}
                ],"depends_on":[]}]}
                """).get(0);

        assertTrue(task.acceptanceCriteria().isEmpty());
    }

    @Test
    void reportsPlansAboveConfiguredTaskLimitWithoutSilentFallback()
        throws Exception {
        LlmTaskPlanner planner = planner(2);

        TaskPlan plan = planner.parsePlan(
            "原始完整问题",
            """
                {"tasks":[
                  {"id":"a","type":"CHAT_TOOL","instruction":"一","depends_on":[]},
                  {"id":"b","type":"CHAT_TOOL","instruction":"二","depends_on":[]},
                  {"id":"c","type":"IMAGE_GENERATION","instruction":"三","depends_on":[]}
                ]}
                """);

        assertTrue(plan.limitExceeded());
        assertEquals(3, plan.detectedTaskCount());
        assertEquals(2, plan.maxPlannedTasks());
        assertTrue(plan.tasks().isEmpty());
        assertTrue(plan.userMessage().contains("3 项任务"));
    }

    @Test
    void parsesImageUnderstandingAndDocumentAnalysisTasks() throws Exception {
        LlmTaskPlanner planner = planner(5);

        List<AgentTask> tasks = planner.parseTasks(
            "分析附件",
            """
                {"tasks":[
                  {"id":"image","type":"IMAGE_UNDERSTANDING","instruction":"分析图片","depends_on":[]},
                  {"id":"document","type":"DOCUMENT_ANALYSIS","instruction":"总结文档","depends_on":[]}
                ]}
                """);

        assertEquals(AgentTaskType.IMAGE_UNDERSTANDING, tasks.get(0).type());
        assertEquals(AgentTaskType.DOCUMENT_ANALYSIS, tasks.get(1).type());
    }

    @Test
    void parsesBilibiliOperationsAsSkillTasks() throws Exception {
        LlmTaskPlanner planner = planner(10);

        List<AgentTask> tasks = planner.parseTasks(
            "订阅牧神记，然后设置电影推送时间20:00",
            """
                {"tasks":[
                  {"id":"subscribe","type":"SKILL","skill_name":"bilibili","instruction":"订阅牧神记","depends_on":[]},
                  {"id":"schedule","type":"SKILL","skill_name":"bilibili","instruction":"设置电影推送时间20:00","depends_on":[]}
                ]}
                """);

        assertEquals(2, tasks.size());
        assertEquals(AgentTaskType.SKILL, tasks.get(0).type());
        assertEquals("bilibili", tasks.get(0).skillName());
        assertEquals(AgentTaskType.SKILL, tasks.get(1).type());
    }

    @Test
    void preservesContentDependencyForDocumentAndVoiceSkills() throws Exception {
        LlmTaskPlanner planner = planner(10);

        List<AgentTask> tasks = planner.parseTasks(
            "写故事，生成Word并用男声朗读",
            """
                {"tasks":[
                  {"id":"content","type":"CHAT_TOOL","skill_name":"","instruction":"写一个故事","depends_on":[]},
                  {"id":"document","type":"SKILL","skill_name":"document-generation","instruction":"生成Word文档","depends_on":["content"]},
                  {"id":"voice","type":"SKILL","skill_name":"voice-reply","instruction":"使用男声朗读","depends_on":["content"]}
                ]}
                """);

        assertEquals(3, tasks.size());
        assertEquals("document-generation", tasks.get(1).skillName());
        assertEquals(List.of("task-1"), tasks.get(1).dependencies());
        assertEquals("voice-reply", tasks.get(2).skillName());
        assertEquals(List.of("task-1"), tasks.get(2).dependencies());
    }

    @Test
    void removesScalarRootReferenceFromVoiceDependencyInput() throws Exception {
        LlmTaskPlanner planner = planner(5);

        List<AgentTask> tasks = planner.parseTasks(
            "用语音回复阜阳天气",
            """
                {"tasks":[
                  {"id":"weather","type":"CHAT_TOOL","instruction":"查询阜阳天气","input":{},"depends_on":[]},
                  {"id":"voice","type":"SKILL","skill_name":"voice-reply","instruction":"语音回复天气","input":{"$ref":"weather.output.weather_info"},"depends_on":["weather"]}
                ]}
                """);

        assertEquals(2, tasks.size());
        assertEquals("voice-reply", tasks.get(1).skillName());
        assertTrue(tasks.get(1).input().isObject());
        assertTrue(tasks.get(1).input().isEmpty());
        assertEquals(List.of("task-1"), tasks.get(1).dependencies());
    }

    @Test
    void fallsBackToChatTaskForInvalidPlannerOutput() throws Exception {
        LlmTaskPlanner planner = planner(5);

        assertEquals(
            List.of(AgentTask.chat("不要丢失的问题")),
            planner.parseTasks("不要丢失的问题", "不是 JSON"));
    }

    private LlmTaskPlanner planner(int maxTasks) {
        DeepSeekClient client = new DeepSeekClient(
            "test-key", "test-model", "https://example.invalid",
            0.8, 100, 1, 1);
        SkillDefinition bilibili = new SkillDefinition(
            "bilibili", "1.0.0", true, "B站", "B站内容管理",
            "bilibili", List.of(), List.of(), 30, true);
        SkillDefinition document = new SkillDefinition(
            "document-generation", "1.0.0", true, "文档生成", "生成文档",
            "document-generation", List.of(), List.of(), 30, false);
        SkillDefinition voice = new SkillDefinition(
            "voice-reply", "1.0.0", true, "语音回复", "生成语音",
            "voice-reply", List.of(), List.of(), 60, false);
        SkillCatalog catalog = new SkillCatalog() {
            @Override
            public List<SkillDefinition> definitions() {
                return List.of(bilibili, document, voice);
            }

            @Override
            public boolean contains(String name) {
                return definitions().stream()
                    .anyMatch(skill -> skill.name().equalsIgnoreCase(name));
            }
        };
        return new LlmTaskPlanner(client, maxTasks, catalog);
    }
}
