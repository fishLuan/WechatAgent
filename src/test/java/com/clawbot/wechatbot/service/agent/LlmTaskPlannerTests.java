package com.clawbot.wechatbot.service.agent;

import com.clawbot.wechatbot.service.client.DeepSeekClient;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
    void rejectsPlansAboveConfiguredTaskLimitWithoutDroppingOriginalRequest()
        throws Exception {
        LlmTaskPlanner planner = planner(2);

        List<AgentTask> tasks = planner.parseTasks(
            "原始完整问题",
            """
                {"tasks":[
                  {"id":"a","type":"CHAT_TOOL","instruction":"一","depends_on":[]},
                  {"id":"b","type":"CHAT_TOOL","instruction":"二","depends_on":[]},
                  {"id":"c","type":"IMAGE_GENERATION","instruction":"三","depends_on":[]}
                ]}
                """);

        assertEquals(List.of(AgentTask.chat("原始完整问题")), tasks);
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
        return new LlmTaskPlanner(client, maxTasks);
    }
}
