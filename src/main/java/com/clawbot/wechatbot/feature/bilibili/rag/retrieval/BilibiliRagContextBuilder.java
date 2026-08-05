package com.clawbot.wechatbot.feature.bilibili.rag.retrieval;

import com.clawbot.wechatbot.feature.bilibili.model.BilibiliPreference;
import com.clawbot.wechatbot.feature.bilibili.model.BilibiliRecommendationHistory;
import com.clawbot.wechatbot.feature.bilibili.model.BilibiliSubscription;
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
import java.util.Set;

@Component
public class BilibiliRagContextBuilder {
    private static final Set<RecommendationState> EXCLUDED_STATES = Set.of(
        RecommendationState.WANT_TO_WATCH,
        RecommendationState.WATCHED,
        RecommendationState.DISLIKED);

    private final BilibiliRagRetrievalService retriever;
    private final BilibiliPreferenceRepository preferences;
    private final BilibiliRecommendationHistoryRepository histories;
    private final BilibiliSubscriptionRepository subscriptions;

    public BilibiliRagContextBuilder(
        BilibiliRagRetrievalService retriever,
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
        UserSignals signals = loadUserSignals(request);
        List<BilibiliRagDocument> documents = retriever.retrieve(
            request.question(),
            request.preferredContentType(),
            request.referenceTitle(),
            24).stream()
            .filter(document -> allowed(document, signals))
            .limit(8)
            .toList();
        return new BilibiliRagContext(
            request, documents, buildUserContext(signals));
    }

    private UserSignals loadUserSignals(BilibiliRagRequest request) {
        BilibiliPreference preference = request.preferredContentType() == null
            ? null
            : preferences.findByWechatUserIdAndContentType(
                request.wechatUserId(), request.preferredContentType()).orElse(null);
        List<BilibiliRecommendationHistory> history = request.preferredContentType() == null
            ? List.of()
            : safeList(histories.findByWechatUserIdAndContentTypeAndStateIn(
                request.wechatUserId(), request.preferredContentType(), EXCLUDED_STATES));
        List<BilibiliSubscription> activeSubscriptions = safeList(
            subscriptions.findByWechatUserIdAndStatus(
                request.wechatUserId(), SubscriptionStatus.ACTIVE));
        return new UserSignals(preference, history, activeSubscriptions);
    }

    private boolean allowed(BilibiliRagDocument document, UserSignals signals) {
        if (signals.preference() != null
            && document.rating() != null
            && document.rating() < signals.preference().getMinimumRating()) {
            return false;
        }
        return signals.history().stream().noneMatch(history ->
            history.getContentType() == document.contentType()
                && java.util.Objects.equals(history.getContentId(), document.contentId())
                && EXCLUDED_STATES.contains(history.getState()));
    }

    private String buildUserContext(UserSignals signals) {
        StringBuilder out = new StringBuilder();
        if (signals.preference() != null) appendPreference(out, signals.preference());
        signals.history().stream()
            .limit(18)
            .forEach(history -> out.append("- ")
                .append(stateLabel(history.getState())).append("：")
                .append(history.getTitle()).append('\n'));
        signals.subscriptions().stream()
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

    private String stateLabel(RecommendationState state) {
        return switch (state) {
            case WANT_TO_WATCH -> "想看";
            case WATCHED -> "看过";
            case DISLIKED -> "不喜欢";
            case RECOMMENDED -> "推荐过";
        };
    }

    private <T> List<T> safeList(List<T> values) {
        return values == null ? List.of() : values;
    }

    private record UserSignals(
        BilibiliPreference preference,
        List<BilibiliRecommendationHistory> history,
        List<BilibiliSubscription> subscriptions
    ) {
    }
}
