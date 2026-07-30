package com.clawbot.wechatbot.feature.bilibili.source;

import com.clawbot.wechatbot.feature.bilibili.model.BilibiliContent;
import com.clawbot.wechatbot.feature.bilibili.model.ContentType;

import java.util.List;
import java.util.Optional;

/**
 * B 站外部数据源的公共契约。
 *
 * <p>采集模块负责实现该接口；推荐和订阅模块只能依赖该接口及公共领域模型，
 * 不得直接依赖页面解析器或外部 DTO。</p>
 */
public interface BilibiliContentSource {
    BilibiliContent resolveUrl(String bilibiliUrl) throws Exception;

    Optional<BilibiliContent> findByContentId(
        ContentType contentType, String contentId) throws Exception;

    Optional<BilibiliContent> findBySeasonId(
        ContentType contentType, String seasonId) throws Exception;

    List<BilibiliContent> findCandidates(
        ContentType contentType, int limit) throws Exception;

    default List<BilibiliContent> searchByTitle(String title, int limit)
        throws Exception {
        return List.of();
    }

    BilibiliContent refresh(BilibiliContent content) throws Exception;
}
