package com.clawbot.wechatbot.tools;

/** function-calling 工具日志回调，方便在不同项目里接入 SLF4J、控制台或测试探针。 */
@FunctionalInterface
public interface FunctionToolLogger {
    FunctionToolLogger NOOP = message -> { };
    FunctionToolLogger CONSOLE = System.out::println;

    void log(String message);
}
