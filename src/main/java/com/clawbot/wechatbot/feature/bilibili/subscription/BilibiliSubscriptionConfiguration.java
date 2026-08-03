package com.clawbot.wechatbot.feature.bilibili.subscription;

import com.clawbot.wechatbot.feature.bilibili.repository.BilibiliSubscriptionRepository;
import com.clawbot.wechatbot.feature.bilibili.repository.BilibiliUpdateEventRepository;
import com.clawbot.wechatbot.feature.bilibili.source.BilibiliContentSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.time.Clock;

/** B 站功能开启后装配角色四服务；缺少角色二数据源时启动会明确失败。 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(
    name = "clawbot.bilibili.enabled",
    havingValue = "true"
)
public class BilibiliSubscriptionConfiguration {

    @Bean("bilibiliClock")
    Clock bilibiliClock() {
        return Clock.systemUTC();
    }

    @Bean
    BilibiliUpdateDetector bilibiliUpdateDetector() {
        return new BilibiliUpdateDetector();
    }

    @Bean
    BilibiliUpdateEventService bilibiliUpdateEventService(
        BilibiliUpdateEventRepository repository,
        BilibiliSubscriptionRepository subscriptionRepository,
        @Qualifier("bilibiliClock") Clock clock
    ) {
        return new BilibiliUpdateEventService(
            repository, subscriptionRepository, clock);
    }

    @Bean
    BilibiliSubscriptionCheckService bilibiliSubscriptionCheckService(
        BilibiliSubscriptionRepository repository,
        BilibiliContentSource contentSource,
        BilibiliUpdateDetector detector,
        BilibiliUpdateEventService eventService,
        @Qualifier("bilibiliClock") Clock clock
    ) {
        return new BilibiliSubscriptionCheckService(
            repository, contentSource, detector, eventService, clock);
    }

    @Bean
    BilibiliSubscriptionService bilibiliSubscriptionService(
        BilibiliSubscriptionRepository repository,
        BilibiliContentSource contentSource,
        BilibiliSubscriptionCheckService checkService,
        @Qualifier("bilibiliClock") Clock clock
    ) {
        return new DefaultBilibiliSubscriptionService(
            repository, contentSource, checkService, clock);
    }

    @Bean
    BilibiliSubscriptionScheduler bilibiliSubscriptionScheduler(
        BilibiliSubscriptionRepository repository,
        BilibiliSubscriptionCheckService checkService
    ) {
        return new BilibiliSubscriptionScheduler(repository, checkService);
    }

}
