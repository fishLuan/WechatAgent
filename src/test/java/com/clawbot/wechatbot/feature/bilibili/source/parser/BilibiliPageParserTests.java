package com.clawbot.wechatbot.feature.bilibili.source.parser;

import com.clawbot.wechatbot.feature.bilibili.model.ContentType;
import com.clawbot.wechatbot.feature.bilibili.source.dto.BilibiliContentDto;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BilibiliPageParserTests {
    private final BilibiliPageParser parser = new BilibiliPageParser();

    @Test
    void parsesPgcMovieWithoutEpisodeTrackingType() {
        String json = """
            {
              "code": 0,
              "result": {
                "season_type": 2,
                "type_name": "电影",
                "media_id": 1001,
                "season_id": 2002,
                "title": "测试电影",
                "evaluate": "电影简介",
                "styles": ["科幻", "冒险"],
                "rating": {"score": 9.4},
                "cover": "https://i0.hdslb.com/cover.jpg",
                "episodes": [
                  {"id": 3003, "title": "正片", "long_title": "正片", "link": "https://www.bilibili.com/bangumi/play/ep3003"}
                ],
                "is_finish": 1
              }
            }
            """;

        BilibiliContentDto dto = parser.parsePgcJson(json, "https://page").orElseThrow();

        assertEquals(ContentType.MOVIE, dto.getContentType());
        assertEquals("1001", dto.getContentId());
        assertEquals("2002", dto.getSeasonId());
        assertEquals(9.4, dto.getRating());
        assertEquals("3003", dto.getLatestEpisode().episodeId());
        assertFalse(dto.getContentType().isEpisodeTrackable());
    }

    @Test
    void parsesSearchMediaResultsAndCleansHighlightedTitle() {
        String json = """
            {
              "data": {
                "result": [
                  {
                    "season_type": 1,
                    "season_type_name": "番剧",
                    "media_id": 11,
                    "season_id": 22,
                    "title": "<em class=\\"keyword\\">测试</em>番剧",
                    "media_score": {"score": 9.7},
                    "styles": "热血,奇幻"
                  },
                  {
                    "season_type": 2,
                    "season_type_name": "电影",
                    "media_id": 33,
                    "season_id": 44,
                    "title": "测试电影",
                    "media_score": {"score": 8.8}
                  }
                ]
              }
            }
            """;

        List<BilibiliContentDto> results = parser.parseSearchMediaJson(json, "");

        assertEquals(2, results.size());
        assertEquals(ContentType.BANGUMI, results.get(0).getContentType());
        assertEquals("测试番剧", results.get(0).getTitle());
        assertEquals(ContentType.MOVIE, results.get(1).getContentType());
    }

    @Test
    void parsesPgcIndexResults() {
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
                    "subTitle": "测试简介",
                    "score": "9.6",
                    "cover": "http://i0.hdslb.com/cover.jpg",
                    "link": "https://www.bilibili.com/bangumi/play/ss835",
                    "index_show": "全11话",
                    "is_finish": 1,
                    "first_ep": {"ep_id": 15014}
                  }
                ]
              }
            }
            """;

        List<BilibiliContentDto> results =
            parser.parsePgcIndexJson(json, ContentType.BANGUMI);

        assertEquals(1, results.size());
        assertEquals(ContentType.BANGUMI, results.get(0).getContentType());
        assertEquals("835", results.get(0).getContentId());
        assertEquals("测试番剧", results.get(0).getTitle());
        assertEquals(9.6, results.get(0).getRating());
        assertEquals("15014", results.get(0).getLatestEpisode().episodeId());
    }

    @Test
    void parsesOrdinaryVideoAsSeries() {
        String json = """
            {
              "data": {
                "bvid": "BV1xx411c7mD",
                "cid": 123,
                "title": "测试视频",
                "desc": "视频简介",
                "videos": 1,
                "stat": {"view": 456}
              }
            }
            """;

        BilibiliContentDto dto = parser.parseVideoJson(json, "https://page").orElseThrow();

        assertEquals(ContentType.SERIES, dto.getContentType());
        assertEquals("BV1xx411c7mD", dto.getContentId());
        assertEquals("123", dto.getLatestEpisode().episodeId());
        assertEquals(456L, dto.getViewCount());
    }

    @Test
    void ignoresPgcJsonWithoutRequiredFields() {
        String json = """
            {"code": 0, "result": {"season_type": 1, "rating": {"score": 9.0}}}
            """;

        assertTrue(parser.parsePgcJson(json, "https://page").isEmpty());
    }

    @Test
    void parsesMediaAndUploaderJson() {
        String mediaJson = """
            {
              "result": {
                "media": {
                  "media_id": 28221445,
                  "season_id": 28032,
                  "title": "测试媒体",
                  "type": 1,
                  "type_name": "番剧",
                  "rating": {"score": 9.8},
                  "new_ep": {"id": 276014, "index": "12", "index_show": "全12话"}
                }
              }
            }
            """;
        String uploaderJson = """
            {
              "data": {
                "card": {
                  "mid": "2",
                  "name": "测试UP主",
                  "sign": "签名",
                  "fans": 100
                }
              }
            }
            """;

        BilibiliContentDto media =
            parser.parseMediaJson(mediaJson, "https://media").orElseThrow();
        BilibiliContentDto uploader =
            parser.parseUploaderJson(uploaderJson, "https://space").orElseThrow();

        assertEquals(ContentType.BANGUMI, media.getContentType());
        assertEquals("28221445", media.getContentId());
        assertEquals("276014", media.getLatestEpisode().episodeId());
        assertEquals(ContentType.UPLOADER, uploader.getContentType());
        assertEquals("测试UP主", uploader.getTitle());
        assertEquals(100L, uploader.getViewCount());
    }
}
