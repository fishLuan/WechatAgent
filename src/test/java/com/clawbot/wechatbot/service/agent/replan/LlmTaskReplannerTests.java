package com.clawbot.wechatbot.service.agent.replan;

import com.clawbot.wechatbot.service.agent.AgentTaskType;
import com.clawbot.wechatbot.service.client.DeepSeekClient;
import com.clawbot.wechatbot.skills.SkillCatalog;
import com.clawbot.wechatbot.skills.SkillDefinition;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LlmTaskReplannerTests {
    @Test
    void parsesControlledMutationsAndStructuredReplacementTask() throws Exception {
        LlmTaskReplanner replanner = new LlmTaskReplanner(
            new DeepSeekClient(
                "key", "model", "https://example.invalid", 0, 100, 1, 1),
            emptySkills());

        ReplanResult result = replanner.parse("""
            ```json
            {"reason":"缺少详情","mutations":[
              {"type":"INSERT_BEFORE","target_task_id":"select","reason":"补评分","task":{
                "id":"details","order":1,"type":"CHAT_TOOL","skill_name":"",
                "instruction":"查询候选作品评分","input":{"count":3},
                "expected_output":{"items":"array"},
                "acceptance_criteria":[{"path":"$.items","operator":"NOT_EMPTY","required":true}],
                "depends_on":["search"]
              }}
            ]}
            ```
            """);

        assertEquals(1, result.mutations().size());
        PlanMutation mutation = result.mutations().get(0);
        assertEquals(PlanMutationType.INSERT_BEFORE, mutation.type());
        assertEquals("select", mutation.targetTaskId());
        assertEquals("details", mutation.task().id());
        assertEquals(AgentTaskType.CHAT_TOOL, mutation.task().type());
        assertEquals(3, mutation.task().input().path("count").asInt());
        assertEquals(1, mutation.task().acceptanceCriteria().size());
    }

    @Test
    void dropsUnknownMutationTypes() throws Exception {
        LlmTaskReplanner replanner = new LlmTaskReplanner(
            new DeepSeekClient(
                "key", "model", "https://example.invalid", 0, 100, 1, 1),
            emptySkills());

        ReplanResult result = replanner.parse(
            "{\"mutations\":[{\"type\":\"RUN_SCRIPT\",\"target_task_id\":\"t1\"}]}");

        assertEquals(0, result.mutations().size());
    }

    private SkillCatalog emptySkills() {
        return new SkillCatalog() {
            @Override public List<SkillDefinition> definitions() { return List.of(); }
            @Override public boolean contains(String name) { return false; }
        };
    }
}
