package com.clawbot.wechatbot.service.agent.replan;

/** 兼容旧构造器和测试使用的禁用重规划器。 */
public final class NoOpTaskReplanner implements TaskReplanner {
    @Override
    public ReplanResult replan(ReplanRequest request) {
        return new ReplanResult(java.util.List.of(), "重规划未启用");
    }

    @Override
    public boolean isConfigured() {
        return false;
    }
}
