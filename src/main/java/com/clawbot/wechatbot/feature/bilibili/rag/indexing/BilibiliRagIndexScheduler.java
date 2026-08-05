package com.clawbot.wechatbot.feature.bilibili.rag.indexing;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 定期把最新的 B 站内容快照增量同步到向量集合。 */
@Component
@ConditionalOnProperty(
    name = {
        "clawbot.bilibili.enabled",
        "clawbot.bilibili.rag.vector.enabled"
    },
    havingValue = "true"
)
public class BilibiliRagIndexScheduler {
    private final BilibiliRagIndexService indexService;

    public BilibiliRagIndexScheduler(BilibiliRagIndexService indexService) {
        this.indexService = indexService;
    }

    @Scheduled(
        initialDelayString = "PT45S",
        fixedDelayString = "#{@bilibiliProperties.subscriptionCheckIntervalMinutes * 60000}"
    )
    public void synchronizeIndex() {
        System.out.println("[BILIBILI-RAG] 开始同步向量索引...");
        BilibiliRagIndexService.IndexStats stats = indexService.rebuildAll();
        System.out.println("[BILIBILI-RAG] 向量索引同步完成：新增/更新 "
            + stats.indexed() + "，跳过 " + stats.skipped()
            + "，失败 " + stats.failed());
    }
}
