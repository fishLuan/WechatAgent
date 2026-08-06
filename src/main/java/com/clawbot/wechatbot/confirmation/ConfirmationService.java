package com.clawbot.wechatbot.confirmation;

import com.clawbot.wechatbot.service.agent.AgentRequestContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.Date;

@Service
public class ConfirmationService {
    private static final Duration TTL = Duration.ofMinutes(30);
    private final MongoTemplate mongoTemplate;
    private final ObjectMapper mapper;
    private final ThreadLocal<Boolean> authorized = ThreadLocal.withInitial(() -> false);

    public ConfirmationService(MongoTemplate mongoTemplate, ObjectMapper mapper) {
        this.mongoTemplate = mongoTemplate;
        this.mapper = mapper;
    }

    public PendingConfirmation create(AgentRequestContext context, String toolName,
                                      JsonNode args, RiskDecision risk) throws Exception {
        PendingConfirmation existing = findWaiting(context.userId());
        if (existing != null && existing.getToolName().equals(toolName)
            && existing.getArgumentsJson().equals(mapper.writeValueAsString(args))) return existing;
        long now = System.currentTimeMillis();
        PendingConfirmation pending = new PendingConfirmation();
        pending.setId("CFM-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase());
        pending.setUserId(context.userId());
        pending.setSourceMessageId(context.messageId());
        pending.setToolName(toolName);
        pending.setArgumentsJson(mapper.writeValueAsString(args));
        pending.setOperationSummary(risk.summary());
        pending.setRiskLevel(risk.level());
        pending.setStatus(ConfirmationStatus.WAITING_CONFIRMATION);
        pending.setCreatedAt(now); pending.setUpdatedAt(now);
        pending.setExpiresAt(new Date(now + TTL.toMillis()));
        return mongoTemplate.save(pending);
    }

    public PendingConfirmation createRecovery(
        AgentRequestContext context, String executionId, String summary
    ) throws Exception {
        var args = mapper.createObjectNode().put("execution_id", executionId);
        return create(context, "__agent_checkpoint_recovery__", args,
            new RiskDecision(true, "HIGH", summary));
    }

    public PendingConfirmation findWaiting(String userId) {
        Query query = Query.query(Criteria.where("userId").is(userId)
            .and("status").is(ConfirmationStatus.WAITING_CONFIRMATION)
            .and("expiresAt").gt(new Date()));
        query.with(Sort.by(Sort.Direction.DESC, "createdAt"));
        return mongoTemplate.findOne(query, PendingConfirmation.class);
    }

    public List<PendingConfirmation> waiting(String userId) {
        return mongoTemplate.find(Query.query(Criteria.where("userId").is(userId)
            .and("status").is(ConfirmationStatus.WAITING_CONFIRMATION)
            .and("expiresAt").gt(new Date())), PendingConfirmation.class);
    }

    public PendingConfirmation findForUser(String id, String userId) {
        PendingConfirmation pending = mongoTemplate.findById(id, PendingConfirmation.class);
        return pending != null && userId.equals(pending.getUserId()) ? pending : null;
    }

    public void status(PendingConfirmation pending, ConfirmationStatus status, String result) {
        pending.setStatus(status); pending.setResult(result == null ? "" : result);
        pending.setUpdatedAt(System.currentTimeMillis()); mongoTemplate.save(pending);
    }

    public <T> T authorized(CheckedSupplier<T> action) throws Exception {
        boolean previous = authorized.get(); authorized.set(true);
        try { return action.get(); } finally { authorized.set(previous); }
    }

    public boolean isAuthorized() { return authorized.get(); }

    @FunctionalInterface public interface CheckedSupplier<T> { T get() throws Exception; }
}
