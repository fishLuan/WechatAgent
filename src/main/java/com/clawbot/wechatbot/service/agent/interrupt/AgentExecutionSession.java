package com.clawbot.wechatbot.service.agent.interrupt;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;

public final class AgentExecutionSession {
    private final String executionId;
    private final AgentCancellationToken token = new AgentCancellationToken();
    private final Set<Future<?>> futures = ConcurrentHashMap.newKeySet();

    AgentExecutionSession(String executionId) { this.executionId = executionId; }
    public String executionId() { return executionId; }
    public AgentCancellationToken token() { return token; }
    public void register(Future<?> future) { if (future != null) futures.add(future); }
    public void unregister(Future<?> future) { if (future != null) futures.remove(future); }
    public void requestCancel() {
        token.cancel();
        futures.forEach(future -> future.cancel(true));
    }
}
