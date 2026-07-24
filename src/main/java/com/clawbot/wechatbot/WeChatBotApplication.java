package com.clawbot.wechatbot;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Spring Boot 应用入口。 */
@SpringBootApplication
public class WeChatBotApplication {
    public static void main(String[] args) {
        SpringApplication application =
                new SpringApplication(WeChatBotApplication.class);

        application.setHeadless(false);
        application.run(args);
    }
}
