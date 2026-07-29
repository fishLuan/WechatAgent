package com.clawbot.wechatbot.feature.bilibili.recommendation;

import com.clawbot.wechatbot.feature.bilibili.model.ContentType;
import com.clawbot.wechatbot.feature.bilibili.model.RecommendedContent;
import com.clawbot.wechatbot.feature.bilibili.model.RecommendationState;
import com.clawbot.wechatbot.feature.bilibili.model.BilibiliRecommendationHistory;
import com.clawbot.wechatbot.feature.bilibili.repository.BilibiliRecommendationHistoryRepository;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * 管理推荐历史记录和用户对作品的后续反馈状态。
 *
 * <p>负责记录、查询推荐历史，以及更新用户对作品的态度（想看、看过、不喜欢）。
 * 推荐服务通过本服务做去重和状态判断，不直接操作 Repository。</p>
 */
@Service
public class RecommendationHistoryService {

    private final BilibiliRecommendationHistoryRepository repository;

    public RecommendationHistoryService(BilibiliRecommendationHistoryRepository repository) {
        this.repository = repository;
    }

    /**
     * 查出指定用户对某一内容类型的全部表态状态（想看、看过、不喜欢、已推荐）。
     */
    public List<BilibiliRecommendationHistory> findByUserAndTypeAndStates(
            String wechatUserId,
            ContentType contentType,
            Collection<RecommendationState> states) {
        return repository.findByWechatUserIdAndContentTypeAndStateIn(
            wechatUserId, contentType, states);
    }

    /**
     * 查出需要排除的内容 ID 列表：已表态不想再看到的。
     * <p>排除策略：WATCHED（看过）、DISLIKED（不喜欢）、WANT_TO_WATCH（想看）。
     */
    public List<String> findExcludedContentIds(
            String wechatUserId, ContentType contentType) {
        List<RecommendationState> excludeStates = List.of(
            RecommendationState.WATCHED,
            RecommendationState.DISLIKED,
            RecommendationState.WANT_TO_WATCH);
        return repository
            .findByWechatUserIdAndContentTypeAndStateIn(
                wechatUserId, contentType, excludeStates)
            .stream()
            .map(BilibiliRecommendationHistory::getContentId)
            .toList();
    }

    /**
     * 检查指定内容是否已被推荐过（含任何状态）。
     */
    public boolean exists(String wechatUserId, ContentType contentType, String contentId) {
        return repository.existsByWechatUserIdAndContentTypeAndContentId(
            wechatUserId, contentType, contentId);
    }

    /**
     * 记录一次新的推荐，或更新已存在记录的推荐次数。
     *
     * @return 保存或更新后的历史记录
     */
    public BilibiliRecommendationHistory recordRecommendation(
            String wechatUserId, ContentType contentType,
            String contentId, String title) {
        Optional<BilibiliRecommendationHistory> existing =
            repository.findByWechatUserIdAndContentTypeAndContentId(
                wechatUserId, contentType, contentId);

        if (existing.isPresent()) {
            BilibiliRecommendationHistory history = existing.get();
            history.setTitle(title);
            history.setRecommendationCount(history.getRecommendationCount() + 1);
            history.setLastRecommendedAt(Instant.now());
            history.setUpdatedAt(Instant.now());
            return repository.save(history);
        }

        BilibiliRecommendationHistory history = new BilibiliRecommendationHistory(
            wechatUserId, contentType, contentId);
        history.setTitle(title);
        history.setState(RecommendationState.RECOMMENDED);
        return repository.save(history);
    }

    /**
     * 批量记录推荐结果。
     */
    public void recordRecommendations(
            String wechatUserId, ContentType contentType,
            List<RecommendedContent> items) {
        for (RecommendedContent item : items) {
            recordRecommendation(
                wechatUserId, contentType, item.contentId(), item.title());
        }
    }

    /**
     * 将指定内容的状态更新为想看。
     */
    public void markWantToWatch(
            String wechatUserId, ContentType contentType, String contentId) {
        markWantToWatch(wechatUserId, contentType, contentId, null);
    }

    public void markWantToWatch(
            String wechatUserId, ContentType contentType,
            String contentId, String title) {
        updateState(
            wechatUserId, contentType, contentId,
            title, RecommendationState.WANT_TO_WATCH);
    }

    /**
     * 将指定内容的状态更新为看过。
     */
    public void markWatched(
            String wechatUserId, ContentType contentType, String contentId) {
        markWatched(wechatUserId, contentType, contentId, null);
    }

    public void markWatched(
            String wechatUserId, ContentType contentType,
            String contentId, String title) {
        updateState(
            wechatUserId, contentType, contentId,
            title, RecommendationState.WATCHED);
    }

    /**
     * 将指定内容的状态更新为不喜欢。
     */
    public void markDisliked(
            String wechatUserId, ContentType contentType, String contentId) {
        markDisliked(wechatUserId, contentType, contentId, null);
    }

    public void markDisliked(
            String wechatUserId, ContentType contentType,
            String contentId, String title) {
        updateState(
            wechatUserId, contentType, contentId,
            title, RecommendationState.DISLIKED);
    }

    // ---- internal ----

    private void updateState(
            String wechatUserId, ContentType contentType,
            String contentId, String title, RecommendationState newState) {
        Optional<BilibiliRecommendationHistory> existing =
            repository.findByWechatUserIdAndContentTypeAndContentId(
                wechatUserId, contentType, contentId);

        if (existing.isPresent()) {
            BilibiliRecommendationHistory history = existing.get();
            history.setState(newState);
            if (title != null && !title.isBlank()) {
                history.setTitle(title.trim());
            }
            history.setStateChangedAt(Instant.now());
            history.setUpdatedAt(Instant.now());
            repository.save(history);
        } else {
            BilibiliRecommendationHistory history = new BilibiliRecommendationHistory(
                wechatUserId, contentType, contentId);
            history.setState(newState);
            history.setTitle(title == null ? "" : title.trim());
            repository.save(history);
        }
    }
}
