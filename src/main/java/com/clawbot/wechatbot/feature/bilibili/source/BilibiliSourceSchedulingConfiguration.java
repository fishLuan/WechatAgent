package com.clawbot.wechatbot.feature.bilibili.source;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** B 站功能启用后，开启采集模块自己的定时抓取。 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(
    name = "clawbot.bilibili.enabled",
    havingValue = "true"
)
public class BilibiliSourceSchedulingConfiguration {
}
