package com.clawbot.wechatbot.service.agent;

/**
 * 为当前执行线程绑定Agent请求身份。
 *
 * <p>上下文由任务提交方显式绑定并在finally语义下恢复，线程池线程不会
 * 继承或长期保留其他用户身份。</p>
 */
public final class AgentRequestContextHolder {
    private final ThreadLocal<AgentRequestContext> current = new ThreadLocal<>();

    public Scope open(AgentRequestContext context) {
        AgentRequestContext previous = current.get();
        AgentRequestContext actual =
            context == null ? AgentRequestContext.anonymous() : context;
        current.set(actual);
        return new Scope(previous);
    }

    public AgentRequestContext current() {
        AgentRequestContext context = current.get();
        return context == null ? AgentRequestContext.anonymous() : context;
    }

    public String currentUserId() {
        return current().userId();
    }

    public <T> T callWith(
        AgentRequestContext context,
        ThrowingSupplier<T> action
    ) throws Exception {
        try (Scope ignored = open(context)) {
            return action.get();
        }
    }

    @FunctionalInterface
    public interface ThrowingSupplier<T> {
        T get() throws Exception;
    }

    public final class Scope implements AutoCloseable {
        private final AgentRequestContext previous;
        private boolean closed;

        private Scope(AgentRequestContext previous) {
            this.previous = previous;
        }

        @Override
        public void close() {
            if (closed) return;
            closed = true;
            if (previous == null) {
                current.remove();
            } else {
                current.set(previous);
            }
        }
    }
}
