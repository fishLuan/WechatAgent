package com.clawbot.wechatbot.feature.bilibili.rag.retrieval;

import com.clawbot.wechatbot.feature.bilibili.model.BilibiliPreference;
import com.clawbot.wechatbot.feature.bilibili.model.RecommendationState;
import com.clawbot.wechatbot.feature.bilibili.model.SubscriptionStatus;
import com.clawbot.wechatbot.feature.bilibili.rag.model.BilibiliRagContext;
import com.clawbot.wechatbot.feature.bilibili.rag.model.BilibiliRagDocument;
import com.clawbot.wechatbot.feature.bilibili.rag.model.BilibiliRagRequest;
import com.clawbot.wechatbot.feature.bilibili.repository.BilibiliPreferenceRepository;
import com.clawbot.wechatbot.feature.bilibili.repository.BilibiliRecommendationHistoryRepository;
import com.clawbot.wechatbot.feature.bilibili.repository.BilibiliSubscriptionRepository;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class BilibiliRagContextBuilder {
    private final BilibiliRagRetriever retriever;
    private final BilibiliPreferenceRepository preferences;
    private final BilibiliRecommendationHistoryRepository histories;
    private final BilibiliSubscriptionRepository subscriptions;

    public BilibiliRagContextBuilder(
        BilibiliRagRetriever retriever,
        BilibiliPreferenceRepository preferences,
        BilibiliRecommendationHistoryRepository histories,
        BilibiliSubscriptionRepository subscriptions
    ) {
        this.retriever = retriever;
        this.preferences = preferences;
        this.histories = histories;
        this.subscriptions = subscriptions;
    }

    public BilibiliRagContext build(BilibiliRagRequest request) {
        List<BilibiliRagDocument> documents = retriever.retrieve(
            request.question(),
            request.preferredContentType(),
            request.referenceTitle(),
            8);
        return new BilibiliRagContext(
            request, documents, buildUserContext(request));
    }

    private String buildUserContext(BilibiliRagRequest request) {
        StringBuilder out = new StringBuilder();
        if (request.preferredContentType() != null) {
            preferences.findByWechatUserIdAndContentType(
                request.wechatUserId(), request.preferredContentType())
                .ifPresent(preference -> appendPreference(out, preference));
        }
        List.of(RecommendationState.WANT_TO_WATCH,
            RecommendationState.WATCHED,
            RecommendationState.DISLIKED)
            .forEach(state -> appendHistory(out, request, state));
        subscriptions.findByWechatUserIdAndStatus(
            request.wechatUserId(), SubscriptionStatus.ACTIVE)
            .stream()
            .limit(8)
            .forEach(subscription -> out.append("- 订阅中：")
                .append(subscription.getTitle())
                .append("，类型：").append(subscription.getContentType())
                .append("，最新集数：")
                .append(subscription.getLastKnownEpisodeNumber() == null
                    ? "未知" : subscription.getLastKnownEpisodeNumber())
                .append('\n'));
        return out.toString().trim();
    }

    private void appendPreference(StringBuilder out, BilibiliPreference preference) {
        out.append("- 偏好类型：").append(preference.getContentType())
            .append("，最低评分：").append(preference.getMinimumRating())
            .append("，推荐数量：").append(preference.getRecommendationCount());
        if (!preference.getPreferredGenres().isEmpty()) {
            out.append("，偏好题材：").append(String.join("、", preference.getPreferredGenres()));
        }
        out.append('\n');
    }

    private void appendHistory(
        StringBuilder out, BilibiliRagRequest request, RecommendationState state
    ) {
        if (request.preferredContentType() == null) return;
        histories.findByWechatUserIdAndContentTypeAndStateIn(
            request.wechatUserId(), request.preferredContentType(), List.of(state))
            .stream()
            .limit(6)
            .forEach(history -> out.append("- ")
                .append(stateLabel(state)).append("：")
                .append(history.getTitle()).append('\n'));
    }

    private String stateLabel(RecommendationState state) {
        return switch (state) {
            case WANT_TO_WATCH -> "想看";
            case WATCHED -> "看过";
            case DISLIKED -> "不喜欢";
            case RECOMMENDED -> "推荐过";
        };
    }
}
