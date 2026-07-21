package com.luanxv.pre;

import com.luanxv.pre.wechatAI.config.BotConfig;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(BotConfig.class)
public class PreApplication {
    public static void main(String[] args) {
        SpringApplication.run(PreApplication.class, args);
    }
}
