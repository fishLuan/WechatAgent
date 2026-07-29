package com.clawbot.wechatbot.feature.bilibili.source.parser;

import com.clawbot.wechatbot.feature.bilibili.model.ContentType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BilibiliUrlParserTests {
    private final BilibiliUrlParser parser = new BilibiliUrlParser();

    @Test
    void parsesBangumiSeasonUrl() {
        BilibiliUrlParser.ParsedBilibiliUrl parsed =
            parser.parse("https://www.bilibili.com/bangumi/play/ss12345").orElseThrow();

        assertEquals(ContentType.BANGUMI, parsed.contentType());
        assertEquals("season", parsed.idType());
        assertEquals("12345", parsed.contentId());
    }

    @Test
    void parsesMovieSeasonUrlWithoutTreatingItAsSeries() {
        BilibiliUrlParser.ParsedBilibiliUrl parsed =
            parser.parse("https://www.bilibili.com/movie/index/?season_id=9876").orElseThrow();

        assertEquals(ContentType.MOVIE, parsed.contentType());
        assertEquals("9876", parsed.contentId());
    }

    @Test
    void parsesBvidAndUploaderUrls() {
        BilibiliUrlParser.ParsedBilibiliUrl video =
            parser.parse("m.bilibili.com/video/BV1xx411c7mD?p=2").orElseThrow();
        BilibiliUrlParser.ParsedBilibiliUrl uploader =
            parser.parse("https://space.bilibili.com/112233").orElseThrow();

        assertEquals(ContentType.SERIES, video.contentType());
        assertEquals("BV1xx411c7mD", video.contentId());
        assertEquals(ContentType.UPLOADER, uploader.contentType());
        assertEquals("112233", uploader.contentId());
    }

    @Test
    void parsesMediaLiveAndDynamicUrls() {
        BilibiliUrlParser.ParsedBilibiliUrl media =
            parser.parse("https://www.bilibili.com/bangumi/media/md28221445").orElseThrow();
        BilibiliUrlParser.ParsedBilibiliUrl live =
            parser.parse("https://live.bilibili.com/6").orElseThrow();
        BilibiliUrlParser.ParsedBilibiliUrl dynamic =
            parser.parse("https://www.bilibili.com/opus/123456").orElseThrow();

        assertEquals("media", media.idType());
        assertEquals("28221445", media.contentId());
        assertEquals("live", live.idType());
        assertEquals("6", live.contentId());
        assertEquals("dynamic", dynamic.idType());
        assertEquals("123456", dynamic.contentId());
    }

    @Test
    void rejectsNonBilibiliUrl() {
        assertTrue(parser.parse("https://example.com/video/BV1xx411c7mD").isEmpty());
    }
}
