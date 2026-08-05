package com.clawbot.wechatbot.feature.bilibili.source;

import com.clawbot.wechatbot.feature.bilibili.model.BilibiliContent;
import com.clawbot.wechatbot.feature.bilibili.model.BilibiliCrawlState;
import com.clawbot.wechatbot.feature.bilibili.model.ContentType;
import com.clawbot.wechatbot.feature.bilibili.repository.BilibiliCrawlStateRepository;
import com.clawbot.wechatbot.feature.bilibili.source.client.BilibiliHttpClient;
import com.clawbot.wechatbot.feature.bilibili.source.parser.BilibiliPageParser;
import com.clawbot.wechatbot.feature.bilibili.source.parser.BilibiliUrlParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PublicPageBilibiliSourceTests {

    @Test
    void returnsEmptyCandidatesWhenAnonymousSearchIsPermissionLimited()
        throws Exception {
        PublicPageBilibiliSource source = new PublicPageBilibiliSource(
            new StubBilibiliHttpClient(null),
            new BilibiliUrlParser(),
            new BilibiliPageParser(), new ObjectMapper());

        assertTrue(source.findCandidates(ContentType.MOVIE, 3).isEmpty());
    }

    @Test
    void findsCandidatesFromPgcIndexBeforeSearchFallback() throws Exception {
        String json = """
            {
              "code": 0,
              "data": {
                "list": [
                  {
                    "media_id": 835,
                    "season_id": 835,
                    "season_type": 1,
                    "title": "测试番剧",
                    "score": "9.6",
                    "link": "https://www.bilibili.com/bangumi/play/ss835"
                  }
                ]
              }
            }
            """;
        StubBilibiliHttpClient http = new StubBilibiliHttpClient(json);
        PublicPageBilibiliSource source = new PublicPageBilibiliSource(
            http,
            new BilibiliUrlParser(),
            new BilibiliPageParser(), new ObjectMapper());

        List<BilibiliContent> bangumi = source.findCandidates(ContentType.BANGUMI, 3);

        assertEquals(1, bangumi.size());
        assertEquals(ContentType.BANGUMI, bangumi.get(0).getContentType());
        assertEquals("835", bangumi.get(0).getContentId());
    }

    @Test
    void findsCandidatesFromPgcRankAsPrimarySource() throws Exception {
        String json = """
            {
              "code": 0,
              "result": {"list": [{
                "season_id": 109700,
                "title": "假面骑士ZZZ",
                "rating": "9.8分",
                "cover": "https://example.test/cover.jpg",
                "url": "https://www.bilibili.com/bangumi/play/ss109700",
                "stat": {"view": 169645130},
                "new_ep": {"index_show": "更新至第46话"}
              }]}
            }
            """;
        StubBilibiliHttpClient http = new StubBilibiliHttpClient(json);
        PublicPageBilibiliSource source = new PublicPageBilibiliSource(
            http,
            new BilibiliUrlParser(),
            new BilibiliPageParser(), new ObjectMapper());

        List<BilibiliContent> results = source.findCandidates(ContentType.BANGUMI, 50);

        assertEquals(1, results.size());
        assertEquals("109700", results.get(0).getSeasonId());
        assertEquals(9.8, results.get(0).getRating());
        assertEquals(169645130L, results.get(0).getViewCount());
        assertEquals(46, results.get(0).getLatestEpisodeNumber());
    }

    @Test
    void doesNotUseBlockedAnonymousSearchForAutomaticCandidates() throws Exception {
        String json = """
            {
              "data": {
                "result": [
                  {"season_type": 1, "season_type_name": "番剧", "media_id": 11, "season_id": 22, "title": "测试番剧"},
                  {"season_type": 2, "season_type_name": "电影", "media_id": 33, "season_id": 44, "title": "测试电影"}
                ]
              }
            }
            """;
        StubBilibiliHttpClient http = new StubBilibiliHttpClient(json);
        PublicPageBilibiliSource source = new PublicPageBilibiliSource(
            http,
            new BilibiliUrlParser(),
            new BilibiliPageParser(), new ObjectMapper());

        List<BilibiliContent> movies = source.findCandidates(ContentType.MOVIE, 3);

        assertTrue(movies.isEmpty());
        assertEquals(null, http.lastAnonymousSearchUrl());
    }

    @Test
    void findsPgcSeriesBySeasonId() throws Exception {
        String json = """
            {
              "code": 0,
              "result": {
                "season_type": 5,
                "media_id": 28223067,
                "season_id": 38729,
                "title": "老友记 第一季",
                "episodes": []
              }
            }
            """;
        StubBilibiliHttpClient http =
            new StubBilibiliHttpClient(json);
        PublicPageBilibiliSource source =
            new PublicPageBilibiliSource(
                http,
                new BilibiliUrlParser(),
                new BilibiliPageParser(), new ObjectMapper());

        BilibiliContent content = source.findBySeasonId(
            ContentType.SERIES, "38729").orElseThrow();

        assertEquals(ContentType.SERIES, content.getContentType());
        assertEquals("28223067", content.getContentId());
        assertEquals("38729", content.getSeasonId());
        assertTrue(http.lastTextUrl().contains("season_id=38729"));
    }

    @Test
    void findsPgcContentByMediaIdInsteadOfTreatingItAsSeasonId()
        throws Exception {
        String json = """
            {
              "code": 0,
              "result": {
                "media": {
                  "type": 1,
                  "type_name": "番剧",
                  "media_id": 28368476,
                  "season_id": 82954,
                  "title": "测试番剧"
                }
              }
            }
            """;
        StubBilibiliHttpClient http =
            new StubBilibiliHttpClient(json);
        PublicPageBilibiliSource source =
            new PublicPageBilibiliSource(
                http,
                new BilibiliUrlParser(),
                new BilibiliPageParser(), new ObjectMapper());

        BilibiliContent content = source.findByContentId(
            ContentType.BANGUMI, "28368476").orElseThrow();

        assertEquals("28368476", content.getContentId());
        assertEquals("82954", content.getSeasonId());
        assertTrue(http.lastTextUrl().contains(
            "media_id=28368476"));
    }

    @Test
    void searchesRelatedWorksByTitleAndDeduplicatesResults()
        throws Exception {
        String json = """
            {
              "data": {
                "result": [
                  {
                    "season_type": 5,
                    "media_id": 28223067,
                    "season_id": 38729,
                    "title": "老友记 第一季",
                    "url": "https://www.bilibili.com/bangumi/play/ss38729",
                    "index_show": "全24集",
                    "media_score": {"score": 9.9}
                  },
                  {
                    "season_type": 5,
                    "media_id": 28223068,
                    "season_id": 38730,
                    "title": "老友记 第二季",
                    "url": "https://www.bilibili.com/bangumi/play/ss38730",
                    "media_score": {"score": 9.8}
                  }
                ]
              }
            }
            """;
        StubBilibiliHttpClient http =
            new StubBilibiliHttpClient(json);
        PublicPageBilibiliSource source =
            new PublicPageBilibiliSource(
                http,
                new BilibiliUrlParser(),
                new BilibiliPageParser(), new ObjectMapper());

        List<BilibiliContent> results =
            source.searchByTitle("老友记", 5);

        assertEquals(2, results.size());
        assertEquals("老友记 第一季", results.get(0).getTitle());
        assertTrue(results.get(0).isFinished());
        assertTrue(http.lastTextUrl()
            .contains("/wbi/search/type"));
        assertTrue(http.lastTextUrl().contains("w_rid="));
    }

    @Test
    void reportsMissingContentWhenBilibiliReturnsNotFound() {
        PublicPageBilibiliSource source = new PublicPageBilibiliSource(
            new StubBilibiliHttpClient("{\"code\":-404,\"message\":\"啥都木有\"}"),
            new BilibiliUrlParser(),
            new BilibiliPageParser(), new ObjectMapper());

        IllegalArgumentException error = assertThrows(
            IllegalArgumentException.class,
            () -> source.resolveUrl("https://www.bilibili.com/bangumi/play/ep691680"));

        assertEquals("该 B 站内容不存在或已下架", error.getMessage());
    }

    @Test
    void rejectsExpiredSearchSessionInsteadOfReturningNoResults() {
        PublicPageBilibiliSource source = new PublicPageBilibiliSource(
            new StubBilibiliHttpClient(
                "{\"errcode\":-14,\"errmsg\":\"session timeout\"}"),
            new BilibiliUrlParser(),
            new BilibiliPageParser(), new ObjectMapper());

        IllegalStateException error = assertThrows(
            IllegalStateException.class,
            () -> source.searchByTitle("魁拔", 5));

        assertTrue(error.getMessage().contains("访问限制"));
    }

    @Test
    void continuesCandidatePagingFromPersistentCursor() throws Exception {
        String json = """
            {"code":0,"data":{"list":[{
              "media_id":835,"season_id":835,"season_type":1,
              "title":"测试番剧","score":"9.6",
              "link":"https://www.bilibili.com/bangumi/play/ss835"
            }]}}
            """;
        BilibiliCrawlStateRepository states =
            mock(BilibiliCrawlStateRepository.class);
        when(states.findById(ContentType.BANGUMI.name()))
            .thenReturn(Optional.of(
                new BilibiliCrawlState(ContentType.BANGUMI, 7)));
        when(states.save(any(BilibiliCrawlState.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));
        StubBilibiliHttpClient http = new StubBilibiliHttpClient(json);
        PublicPageBilibiliSource source = new PublicPageBilibiliSource(
            http, new BilibiliUrlParser(), new BilibiliPageParser(),
            new ObjectMapper(), states);

        source.findCandidates(ContentType.BANGUMI, 3);

        assertTrue(http.lastTextUrl().contains("page=7"));
        verify(states).save(any(BilibiliCrawlState.class));
    }

    private static class StubBilibiliHttpClient extends BilibiliHttpClient {
        private final String anonymousSearchBody;
        private String lastTextUrl;
        private String lastAnonymousSearchUrl;

        StubBilibiliHttpClient(String anonymousSearchBody) {
            super(HttpClient.newHttpClient(), Duration.ofSeconds(1), 0);
            this.anonymousSearchBody = anonymousSearchBody;
        }

        @Override
        public String getAnonymousSearchText(String url) {
            lastAnonymousSearchUrl = url;
            return anonymousSearchBody;
        }

        @Override
        public String getText(String url) {
            lastTextUrl = url;
            if (url.endsWith("/x/web-interface/nav")) {
                return "{\"data\":{\"wbi_img\":{"
                    + "\"img_url\":\"https://i0.hdslb.com/bfs/wbi/"
                    + "0123456789abcdef0123456789abcdef.png\","
                    + "\"sub_url\":\"https://i0.hdslb.com/bfs/wbi/"
                    + "fedcba9876543210fedcba9876543210.png\"}}}";
            }
            return anonymousSearchBody;
        }

        String lastTextUrl() {
            return lastTextUrl;
        }

        String lastAnonymousSearchUrl() {
            return lastAnonymousSearchUrl;
        }
    }
}
