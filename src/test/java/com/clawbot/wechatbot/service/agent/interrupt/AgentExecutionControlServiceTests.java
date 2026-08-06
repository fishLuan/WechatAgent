package com.clawbot.wechatbot.service.agent.interrupt;

import com.clawbot.wechatbot.service.agent.AgentRequestContext;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentExecutionControlServiceTests {
    @Test
    void cancelsCurrentUserExecutionImmediately() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        Map<String, AgentRunRecord> records = new ConcurrentHashMap<>();
        when(mongo.save(any(AgentRunRecord.class))).thenAnswer(invocation -> {
            AgentRunRecord record = invocation.getArgument(0);
            records.put(record.getId(), record);
            return record;
        });
        when(mongo.findById(any(String.class),
            org.mockito.ArgumentMatchers.eq(AgentRunRecord.class)))
            .thenAnswer(invocation -> records.get(invocation.getArgument(0)));
        AgentExecutionControlService service = new AgentExecutionControlService(mongo);
        AgentExecutionSession session = service.begin(
            new AgentRequestContext("user-1", 10L), "写周报并发送邮件");

        CancelResult result = service.cancelCurrent("user-1");

        assertTrue(result.found());
        assertEquals(session.executionId(), result.executionId());
        assertTrue(session.token().isCancellationRequested());
        assertEquals(AgentRunStatus.CANCELLING,
            records.get(session.executionId()).getStatus());
        assertFalse(service.cancelCurrent("other-user").found());
    }
}
