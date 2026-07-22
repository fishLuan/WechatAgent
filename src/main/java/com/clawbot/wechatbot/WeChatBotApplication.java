package com.clawbot.wechatbot;

/**
 * 机器人启动入口 —— 极简开关
 *
 * 真正的装配、登录、轮询逻辑全部在 WeChatBot 中，
 * 这里只做一件事：new 一个机器人，然后 start()。
 *
 * 这样做的好处：
 *   - 启动入口和业务逻辑解耦
 *   - 将来如果想改成 Spring Boot 启动，只要 @Bean 注入 WeChatBot 就行
 *   - WeChatBot 可以单独被测试/复用
 */
public class WeChatBotApplication {
    public static void main(String[] args) {
        new com.clawbot.wechatbot.WeChatBot().start();
    }
}