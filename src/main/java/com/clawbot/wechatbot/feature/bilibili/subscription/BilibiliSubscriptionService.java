package com.clawbot.wechatbot.feature.bilibili.subscription;

import com.clawbot.wechatbot.feature.bilibili.model.CheckResult;
import com.clawbot.wechatbot.feature.bilibili.model.ContentType;
import com.clawbot.wechatbot.feature.bilibili.model.OperationResult;
import com.clawbot.wechatbot.feature.bilibili.model.SubscriptionResult;
import com.clawbot.wechatbot.feature.bilibili.model.SubscriptionView;

import java.util.List;

/**
 * 追更订阅的公共业务接口。
 *
 * <p>由订阅模块实现，微信交互模块通过本接口新增、查询和管理订阅。
 */
public interface BilibiliSubscriptionService {
    SubscriptionResult subscribeByUrl(String wechatUserId, String bilibiliUrl);

    SubscriptionResult subscribeByContentId(
        String wechatUserId, ContentType contentType, String contentId);

    SubscriptionResult subscribeBySeasonId(
        String wechatUserId, ContentType contentType, String seasonId);

    List<SubscriptionView> listSubscriptions(String wechatUserId);

    OperationResult cancel(String wechatUserId, String subscriptionId);

    OperationResult pause(String wechatUserId, String subscriptionId);

    OperationResult resume(String wechatUserId, String subscriptionId);

    CheckResult checkNow(String wechatUserId);
}
