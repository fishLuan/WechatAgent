package com.clawbot.wechatbot.feature.bilibili.source;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 按配置周期自动刷新 B 站候选池。 */
@Component
@ConditionalOnProperty(
    name = "clawbot.bilibili.enabled",
    havingValue = "true"
)
public class BilibiliContentCrawlScheduler {
    private final BilibiliContentCrawler crawler;

    public BilibiliContentCrawlScheduler(BilibiliContentCrawler crawler) {
        this.crawler = crawler;
    }

    @Scheduled(
        initialDelayString = "#{@bilibiliProperties.candidateCrawlInitialDelayMinutes * 60000}",
        fixedDelayString = "#{@bilibiliProperties.candidateCrawlIntervalMinutes * 60000}"
    )
    public void crawlCandidates() {
        BilibiliContentCrawler.CrawlResult result =
            crawler.crawlConfiguredCandidates();
        if (result.hasFailures()) {
            System.err.println("[BILIBILI] 候选池抓取部分失败：候选 "
                + result.candidateCount()
                + " 条，新增 " + result.insertedCount()
                + " 条，更新 " + result.updatedCount()
                + " 条，未变化 " + result.unchangedCount()
                + " 条，明细 " + describeTypes(result)
                + "，失败 " + result.failures());
        } else {
            System.out.println("[BILIBILI] 候选池抓取完成：候选 "
                + result.candidateCount()
                + " 条，新增 " + result.insertedCount()
                + " 条，更新 " + result.updatedCount()
                + " 条，未变化 " + result.unchangedCount()
                + " 条，明细 " + describeTypes(result));
        }
    }

    private String describeTypes(BilibiliContentCrawler.CrawlResult result) {
        return result.typeResults().stream()
            .map(type -> type.contentType()
                + "(候选 " + type.candidateCount()
                + ", 新增 " + type.insertedCount()
                + ", 更新 " + type.updatedCount()
                + ", 未变化 " + type.unchangedCount()
                + ")")
            .toList()
            .toString();
    }
}
