package com.clawbot.wechatbot.config;

import com.clawbot.wechatbot.service.ImageGenService;
import com.clawbot.wechatbot.service.VisionService;
import com.clawbot.wechatbot.service.agent.*;
import com.clawbot.wechatbot.service.agent.guard.AgentExecutionGuard;
import com.clawbot.wechatbot.service.agent.guard.AgentGuardPolicy;
import com.clawbot.wechatbot.service.client.DeepSeekClient;
import com.clawbot.wechatbot.service.impl.DeepSeekChatService;
import com.clawbot.wechatbot.service.longform.LongFormGenerationPolicy;
import com.clawbot.wechatbot.skills.SkillDefinitionLoader;
import com.clawbot.wechatbot.skills.SkillExecutor;
import com.clawbot.wechatbot.skills.SkillManager;
import com.clawbot.wechatbot.tools.FunctionToolRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/** Agent 规划、执行保护、任务处理器和 Skill 的装配。 */
@Configuration(proxyBeanMethods = false)
public class AgentBeanConfiguration {
    @Bean
    AgentGuardPolicy agentGuardPolicy(BotConfig config) {
        return new AgentGuardPolicy(config.getAgentMaxChatDepth(),
            config.getAgentMaxToolCallsPerRound(), config.getAgentMaxTotalToolCalls(),
            config.getAgentMaxSameToolFailures(), config.getAgentMaxToolResultChars(),
            config.getAgentMaxTotalToolResultChars(),
            Duration.ofSeconds(config.getAgentExecutionTimeoutSeconds()));
    }

    @Bean
    AgentExecutionGuard agentExecutionGuard(AgentGuardPolicy policy, ObjectMapper mapper) {
        return new AgentExecutionGuard(policy, mapper);
    }

    @Bean
    DeepSeekChatService singleTaskChatService(
        DeepSeekClient client, FunctionToolRegistry registry, BotConfig config,
        AgentExecutionGuard executionGuard
    ) {
        return new DeepSeekChatService(client, registry, config.getSystemPrompt(),
            config.getDeepSeekMaxToolRounds(), executionGuard,
            new LongFormGenerationPolicy(config.isLongFormEnabled(),
                config.getLongFormMinTargetChars(), config.getLongFormMaxTargetChars(),
                config.getLongFormTolerancePercent(), config.getLongFormMaxContinuationRounds(),
                config.getLongFormMaxTotalChars()));
    }

    @Bean
    SkillManager skillManager(BotConfig config, List<SkillExecutor> executors) {
        return new SkillManager(new SkillDefinitionLoader(config.getSkillClasspathPattern(),
            Path.of(config.getSkillExternalDirectory()), config.getSkillMaxCount(),
            config.getSkillMaxDefinitionBytes()), executors,
            config.isSkillWatchEnabled(), config.getSkillReloadDebounceMillis());
    }

    @Bean TaskPlanner taskPlanner(DeepSeekClient client, BotConfig config, SkillManager skills) {
        return new LlmTaskPlanner(client, config.getAgentMaxPlannedTasks(), skills);
    }
    @Bean MultiTaskPlanningGate multiTaskPlanningGate(TaskPlanner planner, BotConfig config) {
        return new MultiTaskPlanningGate(planner, config.isAgentEnabled());
    }
    @Bean AgentRequestContextHolder agentRequestContextHolder() { return new AgentRequestContextHolder(); }
    @Bean AgentTaskHandler chatAgentTaskHandler(DeepSeekChatService chat) {
        return new ChatAgentTaskHandler(chat);
    }
    @Bean AgentTaskHandler imageGenerationAgentTaskHandler(ImageGenService images) {
        return new ImageGenerationAgentTaskHandler(images);
    }
    @Bean AgentTaskHandler imageUnderstandingAgentTaskHandler(VisionService vision) {
        return new ImageUnderstandingAgentTaskHandler(vision);
    }

    @Bean(destroyMethod = "close")
    AgentOrchestrator agentOrchestrator(
        DeepSeekChatService chat, TaskPlanner planner, List<AgentTaskHandler> handlers,
        AgentRequestContextHolder context, BotConfig config
    ) {
        return new AgentOrchestrator(chat, planner, handlers, config.isAgentEnabled(),
            config.getAgentMaxOuterRounds(), config.getAgentMaxTasksPerBatch(),
            config.getAgentMaxParallelism(),
            Duration.ofSeconds(config.getAgentExecutionTimeoutSeconds()), context);
    }
}
