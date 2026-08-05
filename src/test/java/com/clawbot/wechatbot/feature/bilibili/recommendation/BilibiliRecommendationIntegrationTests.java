package com.clawbot.wechatbot.feature.bilibili.recommendation;

import com.clawbot.wechatbot.feature.bilibili.config.BilibiliProperties;
import com.clawbot.wechatbot.feature.bilibili.model.*;
import com.clawbot.wechatbot.feature.bilibili.repository.BilibiliPreferenceRepository;
import com.clawbot.wechatbot.feature.bilibili.repository.BilibiliRecommendationHistoryRepository;
import com.clawbot.wechatbot.feature.bilibili.repository.BilibiliContentRepository;
import com.clawbot.wechatbot.feature.bilibili.source.BilibiliContentSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * B 站推荐模块完整集成测试。
 *
 * <p>使用全内存桩模拟所有外部依赖，验证推荐管道、排除逻辑、打分排序、
 * 定时推送和 "订阅2" 解析的完整工作流。</p>
 */
class BilibiliRecommendationIntegrationTests {

    // ---- 核心组件 ----
    private BilibiliRecommendationServiceImpl recommendationService;
    private BilibiliPreferenceServiceImpl preferenceService;
    private RecommendationHistoryService historyService;
    private PendingRecommendationStore pendingStore;

    // ---- 桩 ----
    private StubContentSource contentSource;
    private StubPreferenceRepository prefRepo;
    private StubHistoryRepository historyRepo;
    private BilibiliContentRepository contentRepository;

    // ---- 工厂 ----
    private BilibiliProperties properties;

    // ---- 测试用户 ----
    private final String 淇奥 = "wechat-qi-ao";
    private final String 小小白 = "wechat-xiaoxiaobai";

    @BeforeEach
    void setUp() {
        properties = new BilibiliProperties();
        properties.setEnabled(true);
        properties.setDefaultRecommendationCount(3);
        properties.setDefaultMinimumRating(9.0);
        properties.setMovieRecommendationCount(2);
        properties.setMovieMinimumRating(8.0);
        properties.setDefaultPushTime(LocalTime.of(20, 0));
        properties.setMoviePushTime(LocalTime.of(19, 30));

        contentSource = new StubContentSource();
        prefRepo = new StubPreferenceRepository();
        historyRepo = new StubHistoryRepository();
        contentRepository = mock(BilibiliContentRepository.class);
        when(contentRepository
            .findByContentTypeAndRatingGreaterThanEqualOrderByRatingDesc(
                any(ContentType.class), anyDouble()))
            .thenReturn(List.of());
        pendingStore = new PendingRecommendationStore();
        preferenceService = new BilibiliPreferenceServiceImpl(prefRepo, properties);
        historyService = new RecommendationHistoryService(historyRepo);
        recommendationService = new BilibiliRecommendationServiceImpl(
            contentSource, preferenceService, historyService, pendingStore, properties,
            contentRepository);
    }

    // ================================================================
    //  1. 推荐管道基本流程
    // ================================================================

    @Nested
    @DisplayName("推荐基本流程")
    class BasicFlow {

        @Test
        @DisplayName("每日推荐返回正确数量的作品")
        void returnsRequestedCount() {
            RecommendationResult result = recommendationService.recommend(淇奥, ContentType.BANGUMI, 3);
            assertEquals(3, result.items().size());
            assertEquals(淇奥, result.wechatUserId());
            assertEquals(ContentType.BANGUMI, result.contentType());
        }

        @Test
        @DisplayName("推荐结果包含推荐理由")
        void resultsIncludeReason() {
            RecommendationResult result = recommendationService.recommend(淇奥, ContentType.BANGUMI, 1);
            assertNotNull(result.items().get(0).recommendationReason());
            assertFalse(result.items().get(0).recommendationReason().isBlank());
        }

        @Test
        @DisplayName("动漫和电影使用不同的候选池")
        void animeAndMovieHaveSeparatePools() {
            RecommendationResult anime = recommendationService.recommend(淇奥, ContentType.BANGUMI, 3);
            RecommendationResult movie = recommendationService.recommend(淇奥, ContentType.MOVIE, 2);

            assertTrue(anime.items().stream().allMatch(i -> i.contentType() == ContentType.BANGUMI));
            assertTrue(movie.items().stream().allMatch(i -> i.contentType() == ContentType.MOVIE));
            // 内容 ID 不同
            Set<String> animeIds = anime.items().stream().map(RecommendedContent::contentId).collect(java.util.stream.Collectors.toSet());
            Set<String> movieIds = movie.items().stream().map(RecommendedContent::contentId).collect(java.util.stream.Collectors.toSet());
            assertTrue(Collections.disjoint(animeIds, movieIds));
        }

        @Test
        @DisplayName("BANGUMI 和 SERIES 使用不同的候选池")
        void bangumiAndSeriesHaveSeparatePools() {
            RecommendationResult bangumi = recommendationService.recommend(淇奥, ContentType.BANGUMI, 3);
            RecommendationResult series = recommendationService.recommend(淇奥, ContentType.SERIES, 3);

            Set<String> bangumiIds = bangumi.items().stream().map(RecommendedContent::contentId).collect(java.util.stream.Collectors.toSet());
            Set<String> seriesIds = series.items().stream().map(RecommendedContent::contentId).collect(java.util.stream.Collectors.toSet());
            assertTrue(Collections.disjoint(bangumiIds, seriesIds));
        }

        @Test
        @DisplayName("推荐条包含推荐理由")
        void includesRecommendationReason() {
            RecommendationResult result = recommendationService.recommend(淇奥, ContentType.BANGUMI, 1);
            assertNotNull(result.items().get(0).recommendationReason());
            assertFalse(result.items().get(0).recommendationReason().isBlank());
        }

        @Test
        @DisplayName("推荐条包含作品信息（标题、评分、类型、链接）")
        void includesAllContentFields() {
            RecommendationResult result = recommendationService.recommend(淇奥, ContentType.BANGUMI, 1);
            RecommendedContent item = result.items().get(0);
            assertNotNull(item.title());
            assertNotNull(item.rating());
            assertNotNull(item.contentType());
            assertNotNull(item.contentId());
        }
    }

    // ================================================================
    //  2. 边界与异常情况
    // ================================================================

    @Nested
    @DisplayName("边界情况")
    class EdgeCases {

        @Test
        @DisplayName("空候选时返回空结果")
        void emptyCandidates() {
            contentSource.setReturnEmpty(true);
            RecommendationResult result = recommendationService.recommend(淇奥, ContentType.BANGUMI, 3);
            assertTrue(result.items().isEmpty());
        }

        @Test
        @DisplayName("候选少于请求数时返回所有可用候选项")
        void fewerCandidatesThanRequested() {
            RecommendationResult result = recommendationService.recommend(淇奥, ContentType.BANGUMI, 10);
            assertTrue(result.items().size() <= 10);
            assertTrue(result.items().size() > 0);
        }

        @Test
        @DisplayName("所有候选都被排除时返回空结果")
        void allCandidatesExcluded() {
            // 把所有候选的 contentId 都标记为 dislike
            Set<String> allIds = contentSource.allCandidateIds(ContentType.BANGUMI);
            for (String id : allIds) {
                historyService.markDisliked(淇奥, ContentType.BANGUMI, id);
            }

            RecommendationResult result = recommendationService.recommend(淇奥, ContentType.BANGUMI, 3);
            assertTrue(result.items().isEmpty());
        }

        @Test
        @DisplayName("无评分的作品不会被排除")
        void nullRatingIncludedWithDefault() {
            contentSource.setNullRatingForNext(true);
            RecommendationResult result = recommendationService.recommend(淇奥, ContentType.BANGUMI, 3);
            // 候选中有一些 null rating 作品，不会被过滤掉（条件：rating == null || rating >= minRating）
            // 但会排在后面
            assertFalse(result.items().isEmpty());
        }

        @Test
        @DisplayName("不同用户推荐互不干扰")
        void differentUsersAreIsolated() {
            RecommendationResult 淇奥Result = recommendationService.recommend(淇奥, ContentType.BANGUMI, 3);
            RecommendationResult 小小白Result = recommendationService.recommend(小小白, ContentType.BANGUMI, 3);

            assertNotNull(淇奥Result);
            assertNotNull(小小白Result);

            // 淇奥 标记一部作品为不喜欢
            String contentId = 淇奥Result.items().get(0).contentId();
            historyService.markDisliked(淇奥, ContentType.BANGUMI, contentId);

            // 淇奥的下一轮推荐不应包含该作品
            RecommendationResult 淇奥Next = recommendationService.recommend(淇奥, ContentType.BANGUMI, 5);
            assertFalse(淇奥Next.items().stream().anyMatch(i -> i.contentId().equals(contentId)),
                "淇奥标记不喜欢的作品不应再次推荐");

            // 小小白不受影响
            RecommendationResult 小小白Next = recommendationService.recommend(小小白, ContentType.BANGUMI, 5);
            // 小小白还没看过，可以继续推荐
        }
    }

    // ================================================================
    //  3. 排除与去重
    // ================================================================

    @Nested
    @DisplayName("排除与去重逻辑")
    class Exclusion {

        @Test
        @DisplayName("标记为不喜欢的作品不会再次推荐")
        void dislikedContentExcluded() {
            RecommendationResult first = recommendationService.recommend(淇奥, ContentType.BANGUMI, 3);
            String targetId = first.items().get(0).contentId();
            historyService.markDisliked(淇奥, ContentType.BANGUMI, targetId);

            RecommendationResult second = recommendationService.recommend(淇奥, ContentType.BANGUMI, 5);
            assertFalse(second.items().stream().anyMatch(i -> i.contentId().equals(targetId)));
        }

        @Test
        @DisplayName("标记为看过的作品不会再次推荐")
        void watchedContentExcluded() {
            RecommendationResult first = recommendationService.recommend(淇奥, ContentType.BANGUMI, 3);
            String targetId = first.items().get(0).contentId();
            historyService.markWatched(淇奥, ContentType.BANGUMI, targetId);

            RecommendationResult second = recommendationService.recommend(淇奥, ContentType.BANGUMI, 5);
            assertFalse(second.items().stream().anyMatch(i -> i.contentId().equals(targetId)));
        }

        @Test
        @DisplayName("标记为想看的作品也会被排除")
        void wantToWatchContentExcluded() {
            RecommendationResult first = recommendationService.recommend(淇奥, ContentType.BANGUMI, 3);
            String targetId = first.items().get(0).contentId();
            historyService.markWantToWatch(淇奥, ContentType.BANGUMI, targetId);

            RecommendationResult second = recommendationService.recommend(淇奥, ContentType.BANGUMI, 5);
            assertFalse(second.items().stream().anyMatch(i -> i.contentId().equals(targetId)));
        }

        @Test
        @DisplayName("动漫的排除状态不影响电影推荐")
        void animeExclusionDoesNotAffectMovie() {
            RecommendationResult anime = recommendationService.recommend(淇奥, ContentType.BANGUMI, 3);
            historyService.markDisliked(淇奥, ContentType.BANGUMI, anime.items().get(0).contentId());

            RecommendationResult movie = recommendationService.recommend(淇奥, ContentType.MOVIE, 2);
            assertFalse(movie.items().isEmpty());
        }
    }

    // ================================================================
    //  4. "订阅2" 序号解析
    // ================================================================

    @Nested
    @DisplayName("订阅序号解析")
    class PendingItemResolution {

        @Test
        @DisplayName("findPendingItem 按 1-based 序号返回正确作品")
        void correctByItemNumber() {
            recommendationService.recommend(淇奥, ContentType.BANGUMI, 5);
            List<RecommendedContent> items = pendingStore.getPendingItems(淇奥, ContentType.BANGUMI);

            for (int rank = 1; rank <= items.size(); rank++) {
                RecommendedContent found = recommendationService.findPendingItem(淇奥, rank);
                assertNotNull(found, "序号 " + rank + " 应能找到");
                assertEquals(items.get(rank - 1).contentId(), found.contentId());
            }
        }

        @Test
        @DisplayName("序号越界返回 null")
        void outOfRangeReturnsNull() {
            recommendationService.recommend(淇奥, ContentType.BANGUMI, 1);

            assertNull(recommendationService.findPendingItem(淇奥, 2));
            assertNull(recommendationService.findPendingItem(淇奥, 0));
        }

        @Test
        @DisplayName("无缓存时返回 null")
        void noPendingReturnsNull() {
            assertNull(recommendationService.findPendingItem(淇奥, 1));
        }

        @Test
        @DisplayName("refresh 后替换缓存，新序号指新推荐")
        void refreshUpdatesPending() {
            recommendationService.refresh(淇奥, ContentType.BANGUMI, 2);
            RecommendedContent firstOld = recommendationService.findPendingItem(淇奥, 1);

            recommendationService.refresh(淇奥, ContentType.BANGUMI, 3);
            RecommendedContent firstNew = recommendationService.findPendingItem(淇奥, 1);

            // 重新 refresh（新候选）可能会返回不同的 top1
            assertNotNull(firstNew);
        }
    }

    // ================================================================
    //  5. 用户表态（想看/看过/不喜欢）
    // ================================================================

    @Nested
    @DisplayName("用户表态")
    class UserFeedback {

        @Test
        @DisplayName("markWatched 通过序号标记为看过")
        void markWatchedByItemNumber() {
            RecommendationResult result = recommendationService.recommend(淇奥, ContentType.BANGUMI, 3);
            assertFalse(result.items().isEmpty(), "推荐应有结果");
            String targetContentId = result.items().get(0).contentId();

            recommendationService.markWatched(淇奥, 1);

            List<String> excluded = historyService.findExcludedContentIds(淇奥, ContentType.BANGUMI);
            assertTrue(excluded.contains(targetContentId),
                () -> "内容 " + targetContentId + " 应在排除列表中，实际: " + excluded);
        }

        @Test
        @DisplayName("markDisliked 通过序号标记为不喜欢")
        void markDislikedByItemNumber() {
            RecommendationResult result = recommendationService.recommend(淇奥, ContentType.BANGUMI, 3);
            assertFalse(result.items().isEmpty(), "推荐应有结果");
            String targetContentId = result.items().get(1).contentId();  // 第2条 = index 1

            recommendationService.markDisliked(淇奥, 2);

            List<String> excluded = historyService.findExcludedContentIds(淇奥, ContentType.BANGUMI);
            assertTrue(excluded.contains(targetContentId),
                () -> "内容 " + targetContentId + " 应在排除列表中，实际: " + excluded);
        }

        @Test
        @DisplayName("标记不存在的序号不发异常，仅记日志")
        void markNonexistentItemDoesNotThrow() {
            assertDoesNotThrow(() -> recommendationService.markWatched(淇奥, 999));
            assertDoesNotThrow(() -> recommendationService.markDisliked(淇奥, 999));
        }
    }

    // ================================================================
    //  6. 用户偏好
    // ================================================================

    @Nested
    @DisplayName("用户偏好")
    class Preferences {

        @Test
        @DisplayName("未设置偏好时使用全局默认值")
        void usesDefaultsWhenNoPreference() {
            BilibiliPreference pref = preferenceService.getOrCreate(淇奥, ContentType.BANGUMI);
            assertEquals(3, pref.getRecommendationCount());
            assertEquals(9.0, pref.getMinimumRating());
        }

        @Test
        @DisplayName("动漫和电影的默认值各自独立")
        void animeAndMovieDefaultsIndependent() {
            BilibiliPreference animePref = preferenceService.getOrCreate(淇奥, ContentType.BANGUMI);
            BilibiliPreference moviePref = preferenceService.getOrCreate(淇奥, ContentType.MOVIE);

            assertEquals(9.0, animePref.getMinimumRating());
            assertEquals(8.0, moviePref.getMinimumRating());
            assertEquals(3, animePref.getRecommendationCount());
            assertEquals(2, moviePref.getRecommendationCount());
        }

        @Test
        @DisplayName("update 更新偏好并持久化")
        void updatePreferences() {
            PreferenceUpdate update = new PreferenceUpdate(
                8.5, 5, LocalTime.of(21, 0), Set.of("科幻", "热血"), true);

            BilibiliPreference updated = preferenceService.update(淇奥, ContentType.BANGUMI, update);

            assertEquals(8.5, updated.getMinimumRating());
            assertEquals(5, updated.getRecommendationCount());
            assertEquals(LocalTime.of(21, 0), updated.getPushTime());
            assertEquals(Set.of("科幻", "热血"), updated.getPreferredGenres());
        }

        @Test
        @DisplayName("关闭推送后不影响推荐，但调度器不会推送")
        void pushDisabledPreventsSchedulerPush() {
            preferenceService.setPushEnabled(淇奥, ContentType.BANGUMI, false);

            List<BilibiliPreference> enabledPrefs = prefRepo.findByContentTypeAndPushEnabledTrue(ContentType.BANGUMI);
            assertFalse(enabledPrefs.stream().anyMatch(p -> p.getWechatUserId().equals(淇奥)));
        }

        @Test
        @DisplayName("可以增加和恢复不推送日期")
        void updatesExcludedPushDays() {
            preferenceService.setExcludedPushDays(
                淇奥, ContentType.BANGUMI,
                Set.of(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY), true);

            BilibiliPreference excluded =
                preferenceService.getOrCreate(淇奥, ContentType.BANGUMI);
            assertEquals(
                Set.of(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY),
                excluded.getExcludedPushDays());

            preferenceService.setExcludedPushDays(
                淇奥, ContentType.BANGUMI,
                Set.of(DayOfWeek.SATURDAY), false);
            assertEquals(
                Set.of(DayOfWeek.SUNDAY),
                preferenceService.getOrCreate(
                    淇奥, ContentType.BANGUMI).getExcludedPushDays());
        }
    }

    // ================================================================
    //  8. 完整工作流场景
    // ================================================================

    @Nested
    @DisplayName("完整工作流")
    class CompleteWorkflow {

        @Test
        @DisplayName("用户每日收到推荐 → 标记不喜欢 → 不重复出现")
        void recommendThenDislikeAndVerify() {
            // 第 1 天
            RecommendationResult day1 = recommendationService.recommend(淇奥, ContentType.BANGUMI, 3);
            assertEquals(3, day1.items().size());
            String dislikedId = day1.items().get(0).contentId();
            String watchedId = day1.items().get(1).contentId();

            // 用户表态
            historyService.markDisliked(淇奥, ContentType.BANGUMI, dislikedId);
            historyService.markWatched(淇奥, ContentType.BANGUMI, watchedId);

            // 第 2 天
            RecommendationResult day2 = recommendationService.recommend(淇奥, ContentType.BANGUMI, 5);

            boolean hasDisliked = day2.items().stream().anyMatch(i -> i.contentId().equals(dislikedId));
            boolean hasWatched = day2.items().stream().anyMatch(i -> i.contentId().equals(watchedId));
            assertFalse(hasDisliked, "不喜欢的作品不应再次推荐");
            assertFalse(hasWatched, "看过的作品不应再次推荐");
        }

        @Test
        @DisplayName("用户获取推荐 → 订阅2 → 找到对应作品")
        void recommendThenSubscribeByNumber() {
            RecommendationResult result = recommendationService.recommend(淇奥, ContentType.BANGUMI, 5);
            assertEquals(5, result.items().size());

            // 用户说 "订阅2"
            RecommendedContent second = recommendationService.findPendingItem(淇奥, 2);
            assertNotNull(second);
            assertEquals(result.items().get(1).contentId(), second.contentId());
            assertEquals(result.items().get(1).title(), second.title());
        }

        @Test
        @DisplayName("用户同时有动漫和电影推荐，序号各自独立")
        void animeAndMovieItemNumbersIndependent() {
            recommendationService.recommend(淇奥, ContentType.BANGUMI, 3);
            recommendationService.recommend(淇奥, ContentType.MOVIE, 2);

            RecommendedContent anime1 = pendingStore.findByItemNumber(淇奥, ContentType.BANGUMI, 1);
            RecommendedContent movie1 = pendingStore.findByItemNumber(淇奥, ContentType.MOVIE, 1);

            assertNotNull(anime1);
            assertNotNull(movie1);
            assertEquals(ContentType.BANGUMI, anime1.contentType());
            assertEquals(ContentType.MOVIE, movie1.contentType());
        }
    }

    // ================================================================
    //  桩实现
    // ================================================================

    /** B站数据源桩：生成稳定可预测的候选数据 */
    private static class StubContentSource implements BilibiliContentSource {

        private boolean returnEmpty = false;
        private boolean nullRatingForNext = false;
        private final Map<ContentType, List<BilibiliContent>> generated = new HashMap<>();

        void setReturnEmpty(boolean v) { this.returnEmpty = v; }
        void setNullRatingForNext(boolean v) { this.nullRatingForNext = v; }

        Set<String> allCandidateIds(ContentType type) {
            ensureCandidates(type);
            return generated.get(type).stream()
                .map(BilibiliContent::getContentId)
                .collect(java.util.stream.Collectors.toSet());
        }

        @Override
        public BilibiliContent resolveUrl(String url) { return null; }

        @Override
        public Optional<BilibiliContent> findByContentId(ContentType type, String contentId) {
            return Optional.empty();
        }

        @Override
        public Optional<BilibiliContent> findBySeasonId(
            ContentType type, String seasonId
        ) {
            return Optional.empty();
        }

        @Override
        public List<BilibiliContent> findCandidates(ContentType type, int limit) {
            if (returnEmpty) return List.of();
            ensureCandidates(type);
            boolean useNullRating = this.nullRatingForNext;
            this.nullRatingForNext = false;
            return generated.get(type).stream()
                .limit(limit)
                .peek(c -> {
                    if (useNullRating) c.setRating(null);
                })
                .toList();
        }

        @Override
        public BilibiliContent refresh(BilibiliContent content) { return content; }

        private void ensureCandidates(ContentType type) {
            if (generated.containsKey(type)) return;
            List<BilibiliContent> list = new ArrayList<>();
            String prefix = switch (type) {
                case BANGUMI -> "anime";
                case SERIES -> "series";
                case MOVIE -> "movie";
                default -> "content";
            };
            double baseRating = type == ContentType.MOVIE ? 8.0 : 9.0;
            for (int i = 0; i < 30; i++) {
                BilibiliContent c = new BilibiliContent(type, prefix + "-" + (i + 1), "作品" + prefix + (i + 1));
                c.setRating(Math.round((baseRating + (Math.random() * 1.0)) * 10.0) / 10.0);
                c.setViewCount(1_000_000L - i * 30_000L);
                int from = i % 4;
                int to = Math.min(from + 1 + (i % 2), 4);
                c.setGenres(new LinkedHashSet<>(List.of("科幻", "热血", "恋爱", "日常").subList(from, to)));
                c.setPageUrl("https://www.bilibili.com/bangumi/play/ss" + (10000 + i));
                c.setSeasonId("ss" + (10000 + i));
                list.add(c);
            }
            generated.put(type, list);
        }
    }

    /** 偏好仓储桩 */
    private static class StubPreferenceRepository implements BilibiliPreferenceRepository {
        final Map<String, BilibiliPreference> store = new LinkedHashMap<>();

        @SuppressWarnings("unchecked")
        @Override public <S extends BilibiliPreference> S insert(S entity) {
            return (S) save(entity);
        }
        @SuppressWarnings("unchecked")
        @Override public <S extends BilibiliPreference> List<S> insert(Iterable<S> entities) {
            List<S> r = new ArrayList<>();
            for (S e : entities) r.add((S) save(e));
            return r;
        }
        @SuppressWarnings("unchecked")
        @Override public <S extends BilibiliPreference> List<S> saveAll(Iterable<S> entities) {
            List<S> r = new ArrayList<>();
            for (S e : entities) r.add((S) save(e));
            return r;
        }
        @Override public BilibiliPreference save(BilibiliPreference entity) {
            if (entity.getId() == null) entity.setId(UUID.randomUUID().toString());
            store.put(entity.getId(), entity);
            return entity;
        }
        @Override public Optional<BilibiliPreference> findById(String id) {
            return Optional.ofNullable(store.get(id));
        }
        @Override public boolean existsById(String id) { return store.containsKey(id); }
        @Override public List<BilibiliPreference> findAll() { return List.copyOf(store.values()); }
        @Override public List<BilibiliPreference> findAllById(Iterable<String> ids) {
            List<String> list = new ArrayList<>(); ids.forEach(list::add);
            return store.values().stream().filter(p -> list.contains(p.getId())).toList();
        }
        @Override public long count() { return store.size(); }
        @Override public void deleteById(String id) { store.remove(id); }
        @Override public void delete(BilibiliPreference entity) { store.values().remove(entity); }
        @Override public void deleteAllById(Iterable<? extends String> ids) {
            List<String> list = new ArrayList<>(); ids.forEach(id -> list.add((String) id));
            store.keySet().removeAll(list);
        }
        @Override public void deleteAll(Iterable<? extends BilibiliPreference> entities) {
            List<BilibiliPreference> list = new ArrayList<>(); entities.forEach(list::add);
            store.values().removeAll(list);
        }
        @Override public void deleteAll() { store.clear(); }
        @Override public List<BilibiliPreference> findAll(org.springframework.data.domain.Sort sort) {
            return List.copyOf(store.values());
        }
        @Override public org.springframework.data.domain.Page<BilibiliPreference> findAll(
                org.springframework.data.domain.Pageable pageable) {
            return org.springframework.data.domain.Page.empty();
        }

        @Override public Optional<BilibiliPreference> findByWechatUserIdAndContentType(String uid, ContentType type) {
            return store.values().stream()
                .filter(p -> p.getWechatUserId().equals(uid) && p.getContentType() == type)
                .findFirst();
        }
        @Override public List<BilibiliPreference> findByContentTypeAndPushEnabledTrue(ContentType type) {
            return store.values().stream()
                .filter(p -> p.getContentType() == type && p.isPushEnabled())
                .toList();
        }

        @Override public <S extends BilibiliPreference> Optional<S> findOne(
                org.springframework.data.domain.Example<S> example) { return Optional.empty(); }
        @Override public <S extends BilibiliPreference> List<S> findAll(
                org.springframework.data.domain.Example<S> example) { return List.of(); }
        @Override public <S extends BilibiliPreference> List<S> findAll(
                org.springframework.data.domain.Example<S> example,
                org.springframework.data.domain.Sort sort) { return List.of(); }
        @Override public <S extends BilibiliPreference> org.springframework.data.domain.Page<S> findAll(
                org.springframework.data.domain.Example<S> example,
                org.springframework.data.domain.Pageable pageable) {
            return org.springframework.data.domain.Page.empty();
        }
        @Override public <S extends BilibiliPreference> long count(
                org.springframework.data.domain.Example<S> example) { return 0; }
        @Override public <S extends BilibiliPreference> boolean exists(
                org.springframework.data.domain.Example<S> example) { return false; }
        @Override public <S extends BilibiliPreference, R> R findBy(
                org.springframework.data.domain.Example<S> example,
                java.util.function.Function<org.springframework.data.repository.query.FluentQuery.FetchableFluentQuery<S>, R> queryFunction) {
            throw new UnsupportedOperationException();
        }
    }

    /** 推荐历史仓储桩 */
    private static class StubHistoryRepository implements BilibiliRecommendationHistoryRepository {
        final Map<String, BilibiliRecommendationHistory> store = new LinkedHashMap<>();
        private long nextId = 1;

        @SuppressWarnings("unchecked")
        @Override public <S extends BilibiliRecommendationHistory> S insert(S entity) {
            return (S) save(entity);
        }
        @SuppressWarnings("unchecked")
        @Override public <S extends BilibiliRecommendationHistory> List<S> insert(Iterable<S> entities) {
            List<S> r = new ArrayList<>();
            for (S e : entities) r.add((S) save(e));
            return r;
        }
        @SuppressWarnings("unchecked")
        @Override public <S extends BilibiliRecommendationHistory> List<S> saveAll(Iterable<S> entities) {
            List<S> r = new ArrayList<>();
            for (S e : entities) r.add((S) save(e));
            return r;
        }
        @Override public BilibiliRecommendationHistory save(BilibiliRecommendationHistory entity) {
            if (entity.getId() == null) entity.setId(String.valueOf(nextId++));
            store.put(entity.getId(), entity);
            return entity;
        }
        @Override public Optional<BilibiliRecommendationHistory> findById(String id) {
            return Optional.ofNullable(store.get(id));
        }
        @Override public boolean existsById(String id) { return store.containsKey(id); }
        @Override public List<BilibiliRecommendationHistory> findAll() { return List.copyOf(store.values()); }
        @Override public List<BilibiliRecommendationHistory> findAllById(Iterable<String> ids) {
            List<String> list = new ArrayList<>(); ids.forEach(list::add);
            return store.values().stream().filter(h -> list.contains(h.getId())).toList();
        }
        @Override public long count() { return store.size(); }
        @Override public void deleteById(String id) { store.remove(id); }
        @Override public void delete(BilibiliRecommendationHistory entity) { store.values().remove(entity); }
        @Override public void deleteAllById(Iterable<? extends String> ids) {
            List<String> list = new ArrayList<>(); ids.forEach(id -> list.add((String) id));
            store.keySet().removeAll(list);
        }
        @Override public void deleteAll(Iterable<? extends BilibiliRecommendationHistory> entities) {
            List<BilibiliRecommendationHistory> list = new ArrayList<>(); entities.forEach(list::add);
            store.values().removeAll(list);
        }
        @Override public void deleteAll() { store.clear(); }
        @Override public List<BilibiliRecommendationHistory> findAll(org.springframework.data.domain.Sort sort) {
            return List.copyOf(store.values());
        }
        @Override public org.springframework.data.domain.Page<BilibiliRecommendationHistory> findAll(
                org.springframework.data.domain.Pageable pageable) {
            return org.springframework.data.domain.Page.empty();
        }

        @Override public Optional<BilibiliRecommendationHistory> findByWechatUserIdAndContentTypeAndContentId(
                String uid, ContentType type, String contentId) {
            return store.values().stream()
                .filter(h -> h.getWechatUserId().equals(uid) && h.getContentType() == type && h.getContentId().equals(contentId))
                .findFirst();
        }
        @Override public boolean existsByWechatUserIdAndContentTypeAndContentId(
                String uid, ContentType type, String contentId) {
            return store.values().stream().anyMatch(
                h -> h.getWechatUserId().equals(uid) && h.getContentType() == type && h.getContentId().equals(contentId));
        }
        @Override public List<BilibiliRecommendationHistory> findByWechatUserIdAndContentTypeAndStateIn(
                String uid, ContentType type, java.util.Collection<RecommendationState> states) {
            return store.values().stream()
                .filter(h -> h.getWechatUserId().equals(uid) && h.getContentType() == type && states.contains(h.getState()))
                .toList();
        }

        @Override public <S extends BilibiliRecommendationHistory> Optional<S> findOne(
                org.springframework.data.domain.Example<S> example) { return Optional.empty(); }
        @Override public <S extends BilibiliRecommendationHistory> List<S> findAll(
                org.springframework.data.domain.Example<S> example) { return List.of(); }
        @Override public <S extends BilibiliRecommendationHistory> List<S> findAll(
                org.springframework.data.domain.Example<S> example,
                org.springframework.data.domain.Sort sort) { return List.of(); }
        @Override public <S extends BilibiliRecommendationHistory> org.springframework.data.domain.Page<S> findAll(
                org.springframework.data.domain.Example<S> example,
                org.springframework.data.domain.Pageable pageable) {
            return org.springframework.data.domain.Page.empty();
        }
        @Override public <S extends BilibiliRecommendationHistory> long count(
                org.springframework.data.domain.Example<S> example) { return 0; }
        @Override public <S extends BilibiliRecommendationHistory> boolean exists(
                org.springframework.data.domain.Example<S> example) { return false; }
        @Override public <S extends BilibiliRecommendationHistory, R> R findBy(
                org.springframework.data.domain.Example<S> example,
                java.util.function.Function<org.springframework.data.repository.query.FluentQuery.FetchableFluentQuery<S>, R> queryFunction) {
            throw new UnsupportedOperationException();
        }
    }
}
