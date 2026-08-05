package com.clawbot.wechatbot.config;

import com.clawbot.wechatbot.service.ImageGenService;
import com.clawbot.wechatbot.service.VisionService;
import com.clawbot.wechatbot.service.agent.*;
import com.clawbot.wechatbot.service.agent.guard.AgentExecutionGuard;
import com.clawbot.wechatbot.service.agent.guard.AgentGuardPolicy;
import com.clawbot.wechatbot.service.agent.validation.ToolResultValidator;
import com.clawbot.wechatbot.service.agent.validation.ToolValidationPipeline;
import com.clawbot.wechatbot.service.agent.acceptance.DefaultTaskAcceptanceEvaluator;
import com.clawbot.wechatbot.service.agent.acceptance.TaskAcceptanceEvaluator;
import com.clawbot.wechatbot.service.agent.replan.LlmTaskReplanner;
import com.clawbot.wechatbot.service.agent.replan.PlanMutationApplier;
import com.clawbot.wechatbot.service.agent.replan.PlanMutationValidator;
import com.clawbot.wechatbot.service.agent.replan.TaskReplanner;
import com.clawbot.wechatbot.service.agent.replan.AgentReplanPolicy;
import com.clawbot.wechatbot.service.agent.reference.ReferencePolicy;
import com.clawbot.wechatbot.service.agent.reference.ResultReferenceResolver;
import com.clawbot.wechatbot.service.client.DeepSeekClient;
import com.clawbot.wechatbot.service.impl.DeepSeekChatService;
import com.clawbot.wechatbot.service.longform.LongFormGenerationPolicy;
import com.clawbot.wechatbot.skills.SkillDefinitionLoader;
import com.clawbot.wechatbot.skills.SkillExecutor;
import com.clawbot.wechatbot.skills.SkillManager;
import com.clawbot.wechatbot.skills.validation.CompositeTaskAcceptanceEvaluator;
import com.clawbot.wechatbot.skills.validation.SkillResultValidator;
import com.clawbot.wechatbot.skills.validation.SkillResultValidatorRegistry;
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
    ToolValidationPipeline toolValidationPipeline(
        ObjectMapper mapper, List<ToolResultValidator> validators, BotConfig config
    ) {
        return new ToolValidationPipeline(
            mapper, validators, config.getAgentToolValidationMinConfidence());
    }

    @Bean
    SkillResultValidatorRegistry skillResultValidatorRegistry(
        List<SkillResultValidator> validators
    ) {
        return new SkillResultValidatorRegistry(validators);
    }

    @Bean
    TaskAcceptanceEvaluator taskAcceptanceEvaluator(
        ObjectMapper mapper, SkillManager skills,
        SkillResultValidatorRegistry validators
    ) {
        return new CompositeTaskAcceptanceEvaluator(
            new DefaultTaskAcceptanceEvaluator(mapper), skills, validators);
    }

    @Bean
    TaskReplanner taskReplanner(
        DeepSeekClient client, SkillManager skills
    ) {
        return new LlmTaskReplanner(client, skills);
    }

    @Bean
    PlanMutationValidator planMutationValidator(
        SkillManager skills, BotConfig config, ReferencePolicy referencePolicy
    ) {
        return new PlanMutationValidator(
            skills,
            config.getAgentReplanMaxMutations(),
            config.getAgentReplanMaxGeneratedTasks(),
            config.getAgentReplanMaxTotalTasks(),
            referencePolicy);
    }

    @Bean
    PlanMutationApplier planMutationApplier(PlanMutationValidator validator) {
        return new PlanMutationApplier(validator);
    }

    @Bean
    AgentReplanPolicy agentReplanPolicy(BotConfig config) {
        return new AgentReplanPolicy(
            config.isAgentReplanEnabled(),
            config.getAgentReplanMaxCount(),
            config.getAgentReplanMaxRetriesPerTask(),
            config.getAgentReplanMaxTotalTaskExecutions(),
            config.getAgentReplanMaxTotalTasks(),
            Duration.ofSeconds(config.getAgentReplanTimeoutSeconds()));
    }

    @Bean
    ReferencePolicy referencePolicy(BotConfig config) {
        return new ReferencePolicy(
            config.getAgentReferenceMaxPerTask(),
            config.getAgentReferenceMaxDepth(),
            config.getAgentReferenceMaxPathLength(),
            config.getAgentReferenceMaxResolvedInputChars());
    }

    @Bean
    ResultReferenceResolver resultReferenceResolver(
        ObjectMapper mapper, ReferencePolicy policy
    ) {
        return new ResultReferenceResolver(mapper, policy);
    }

    @Bean
    DeepSeekChatService singleTaskChatService(
        DeepSeekClient client, FunctionToolRegistry registry, BotConfig config,
        AgentExecutionGuard executionGuard,
        ToolValidationPipeline validationPipeline
    ) {
        return new DeepSeekChatService(client, registry, config.getSystemPrompt(),
            config.getDeepSeekMaxToolRounds(), executionGuard,
            new LongFormGenerationPolicy(config.isLongFormEnabled(),
                config.getLongFormMinTargetChars(), config.getLongFormMaxTargetChars(),
                config.getLongFormTolerancePercent(), config.getLongFormMaxContinuationRounds(),
                config.getLongFormMaxTotalChars()), validationPipeline);
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
        AgentRequestContextHolder context, BotConfig config,
        TaskAcceptanceEvaluator acceptanceEvaluator,
        TaskReplanner replanner,
        PlanMutationApplier mutationApplier,
        AgentReplanPolicy replanPolicy,
        ResultReferenceResolver referenceResolver,
        com.clawbot.wechatbot.service.agent.interrupt.AgentExecutionControlService executionControl,
        com.clawbot.wechatbot.service.agent.checkpoint.AgentCheckpointStore checkpointStore
    ) {
        return new AgentOrchestrator(chat, planner, handlers, config.isAgentEnabled(),
            config.getAgentMaxOuterRounds(), config.getAgentMaxTasksPerBatch(),
            config.getAgentMaxParallelism(),
            Duration.ofSeconds(config.getAgentExecutionTimeoutSeconds()), context,
            acceptanceEvaluator, replanner, mutationApplier, replanPolicy,
            referenceResolver)
            .enableInterrupts(executionControl)
            .enableCheckpoints(checkpointStore);
    }
}
