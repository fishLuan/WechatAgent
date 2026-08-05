package com.clawbot.wechatbot.service.agent.interrupt;

import com.clawbot.wechatbot.service.agent.AgentRequestContext;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Service;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import jakarta.annotation.PostConstruct;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class AgentExecutionControlService {
    private final MongoTemplate mongoTemplate;
    private final ConcurrentHashMap<String, AgentExecutionSession> activeByUser = new ConcurrentHashMap<>();

    public AgentExecutionControlService(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    @PostConstruct
    void settleInterruptedRunsAfterRestart() {
        Query stale = Query.query(Criteria.where("status").in(
            AgentRunStatus.RUNNING, AgentRunStatus.CANCEL_REQUESTED,
            AgentRunStatus.CANCELLING));
        mongoTemplate.updateMulti(stale,
            new Update().set("status", AgentRunStatus.CANCELLED)
                .set("updatedAt", System.currentTimeMillis()),
            AgentRunRecord.class);
    }

    public AgentExecutionSession begin(AgentRequestContext context, String request) {
        String userId = context == null ? "" : context.userId();
        String id = "TASK-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        AgentExecutionSession session = new AgentExecutionSession(id);
        if (!userId.isBlank()) activeByUser.put(userId, session);
        long now = System.currentTimeMillis();
        AgentRunRecord record = new AgentRunRecord();
        record.setId(id); record.setUserId(userId);
        record.setSourceMessageId(context == null ? null : context.messageId());
        record.setRequest(request); record.setStatus(AgentRunStatus.RUNNING);
        record.setCreatedAt(now); record.setUpdatedAt(now);
        mongoTemplate.save(record);
        return session;
    }

    public CancelResult cancelCurrent(String userId) {
        AgentExecutionSession session = activeByUser.get(userId);
        if (session == null) return new CancelResult(false, "", null);
        update(session.executionId(), AgentRunStatus.CANCEL_REQUESTED, List.of(), List.of(), false);
        session.requestCancel();
        update(session.executionId(), AgentRunStatus.CANCELLING, List.of(), List.of(), false);
        return new CancelResult(true, session.executionId(), AgentRunStatus.CANCELLING);
    }

    public void finish(AgentExecutionSession session, AgentRunStatus status,
                       List<String> completed, List<String> cancelled,
                       boolean sideEffectsCompleted, String userId) {
        update(session.executionId(), status, completed, cancelled, sideEffectsCompleted);
        if (userId != null) activeByUser.remove(userId, session);
    }

    private void update(String id, AgentRunStatus status, List<String> completed,
                        List<String> cancelled, boolean sideEffects) {
        AgentRunRecord record = mongoTemplate.findById(id, AgentRunRecord.class);
        if (record == null) return;
        record.setStatus(status); record.setUpdatedAt(System.currentTimeMillis());
        if (completed != null && !completed.isEmpty()) record.setCompletedTasks(completed);
        if (cancelled != null && !cancelled.isEmpty()) record.setCancelledTasks(cancelled);
        record.setSideEffectsCompleted(sideEffects);
        mongoTemplate.save(record);
    }
}
