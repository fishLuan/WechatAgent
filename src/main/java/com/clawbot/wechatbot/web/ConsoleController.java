package com.clawbot.wechatbot.web;

import com.clawbot.wechatbot.config.BotConfig;
import com.clawbot.wechatbot.memory.ConversationMemory;
import com.clawbot.wechatbot.scheduler.model.ScheduledSubscription;
import com.clawbot.wechatbot.service.agent.AgentRequestContext;
import com.clawbot.wechatbot.service.agent.AgentRequestContextHolder;
import com.clawbot.wechatbot.skills.SkillCatalog;
import com.clawbot.wechatbot.skills.SkillDefinition;
import com.clawbot.wechatbot.tools.FunctionToolRegistry;
import com.clawbot.wechatbot.tools.ToolExecutionOutcome;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 可视化控制台 API：状态、能力清单、对话记录。
 * 能力清单由 FunctionToolRegistry + SkillCatalog 动态生成 —— 团队新增工具/技能后前端自动渲染。
 */
@RestController
@RequestMapping("/api")
public class ConsoleController {

    private final FunctionToolRegistry toolRegistry;
    private final SkillCatalog skillCatalog;
    private final MongoTemplate mongoTemplate;
    private final BotConfig config;
    private final ObjectMapper mapper;
    private final AgentRequestContextHolder contextHolder;
    private final long startedAt = System.currentTimeMillis();

    public ConsoleController(
        FunctionToolRegistry toolRegistry,
        SkillCatalog skillCatalog,
        MongoTemplate mongoTemplate,
        BotConfig config,
        ObjectMapper mapper,
        AgentRequestContextHolder contextHolder
    ) {
        this.toolRegistry = toolRegistry;
        this.skillCatalog = skillCatalog;
        this.mongoTemplate = mongoTemplate;
        this.config = config;
        this.mapper = mapper;
        this.contextHolder = contextHolder;
    }

    /** 机器人运行状态 */
    @GetMapping("/status")
    public Map<String, Object> status() {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("app", "ClawBot WechatAgent");
        node.put("running", true);
        node.put("uptimeSeconds", (System.currentTimeMillis() - startedAt) / 1000);
        node.put("model", config.getDeepSeekModel());
        node.put("toolCount", toolRegistry.size());
        node.put("skillCount", skillCatalog.definitions().size());
        return node;
    }

    /**
     * 能力清单：工具（function-calling）+ 技能（skill.yaml）。
     * 前端数据驱动渲染，新增能力无需改前端。
     */
    @GetMapping("/capabilities")
    public Map<String, Object> capabilities() {
        Map<String, Object> root = new LinkedHashMap<>();

        List<Map<String, Object>> tools = new ArrayList<>();
        toolRegistry.definitions().forEach(def -> {
            Map<String, Object> out = new LinkedHashMap<>();
            JsonNode fn = def.path("function");
            out.put("type", "tool");
            out.put("name", fn.path("name").asText());
            out.put("description", fn.path("description").asText());
            // Jackson2 JsonNode → 普通对象，避免 Jackson3 序列化兼容问题；缺失/空参数给 null
            JsonNode params = fn.path("parameters");
            out.put("parameters", params.isMissingNode() || params.isNull()
                ? null : mapper.convertValue(params, Object.class));
            tools.add(out);
        });

        List<Map<String, Object>> skills = new ArrayList<>();
        for (SkillDefinition s : skillCatalog.definitions()) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("type", "skill");
            out.put("name", s.name());
            out.put("displayName", s.displayName());
            out.put("version", s.version());
            out.put("enabled", s.enabled());
            out.put("description", s.description());
            out.put("capabilities", s.capabilities());
            out.put("triggers", s.triggers());
            skills.add(out);
        }

        root.put("tools", tools);
        root.put("skills", skills);
        root.put("total", tools.size() + skills.size());
        return root;
    }

    /** 对话记录：MongoDB conversation_memory 集合快照 */
    @GetMapping("/history")
    public List<Map<String, Object>> history() {
        List<Map<String, Object>> arr = new ArrayList<>();
        List<ConversationMemory> all = mongoTemplate.find(new Query(), ConversationMemory.class);
        for (ConversationMemory m : all) {
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("userKey", m.getUserKey());
            node.put("namespace", m.getNamespace());
            node.put("longTermSummary", m.getLongTermSummary());
            node.put("turnCounter", m.getTurnCounter());
            node.put("updatedAt", m.getUpdatedAt() == null ? null : m.getUpdatedAt().toString());
            List<Map<String, Object>> msgs = new ArrayList<>();
            m.getRecentMessages().forEach(msg -> {
                Map<String, Object> mn = new LinkedHashMap<>();
                mn.put("role", msg.role());
                mn.put("content", msg.content());
                mn.put("createdAt", msg.createdAt() == null ? null : msg.createdAt().toString());
                msgs.add(mn);
            });
            node.put("messages", msgs);
            arr.add(node);
        }
        return arr;
    }

    /** 简单统计（无埋点，先给能力与任务维度） */
    @GetMapping("/stats")
    public Map<String, Object> stats() {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("tools", toolRegistry.size());
        node.put("skills", skillCatalog.definitions().size());
        node.put("users", mongoTemplate.find(new Query(), ConversationMemory.class).size());
        node.put("tasks", mongoTemplate.findAll(ScheduledSubscription.class).size());
        return node;
    }

    /**
     * 控制台直接调用工具（弹窗交互）。
     * body: { "name": "get_weather", "arguments": { "city": "杭州" }, "userId": "可选" }
     * 传 userId 时以该身份执行（用于 scheduler_manage / bilibili_manage 等微信会话工具）。
     */
    @PostMapping("/tools/execute")
    public ResponseEntity<?> executeTool(@RequestBody Map<String, Object> body) {
        try {
            String name = body.get("name") instanceof String s ? s.trim() : "";
            if (name.isBlank()) {
                return ResponseEntity.badRequest().body(Map.of("error", "缺少工具名称 name"));
            }
            Object argsObj = body.get("arguments");
            Map<String, Object> args = argsObj instanceof Map<?, ?> m
                ? castMap(m) : new LinkedHashMap<>();
            String argsJson = mapper.writeValueAsString(args);

            String userId = body.get("userId") instanceof String s ? s.trim() : "";
            ToolExecutionOutcome outcome;
            if (!userId.isBlank()) {
                AgentRequestContext ctx = new AgentRequestContext(userId, null);
                outcome = contextHolder.callWith(ctx,
                    () -> toolRegistry.executeWithOutcome(name, argsJson));
            } else {
                outcome = toolRegistry.executeWithOutcome(name, argsJson);
            }

            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("tool", name);
            resp.put("success", outcome.success());
            resp.put("content", outcome.content());
            resp.put("code", outcome.code());
            return ResponseEntity.ok(resp);
        } catch (Exception e) {
            return ResponseEntity.badRequest()
                .body(Map.of("error", "工具执行失败：" + e.getMessage()));
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> castMap(Map<?, ?> m) {
        Map<String, Object> out = new LinkedHashMap<>();
        m.forEach((k, v) -> out.put(String.valueOf(k), v));
        return out;
    }
}
