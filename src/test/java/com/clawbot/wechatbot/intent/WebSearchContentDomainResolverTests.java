package com.clawbot.wechatbot.intent;

import com.clawbot.wechatbot.tools.searchonlinetool.WebSearchTool;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WebSearchContentDomainResolverTests {
    private WebSearchContentDomainResolver resolver;

    @AfterEach
    void closeResolver() {
        if (resolver != null) resolver.close();
    }

    @Test
    void detectsTitleThatHasBothBookAndVideoVersions() throws Exception {
        WebSearchTool search = mock(WebSearchTool.class);
        when(search.execute(any(JsonNode.class))).thenReturn("""
            {"success":true,"results":[
              {"title":"三体 刘慈欣科幻小说","snippet":"出版社出版的图书","url":"https://book.douban.com/subject/1"},
              {"title":"三体电视剧","snippet":"主演和导演介绍，共30集","url":"https://movie.douban.com/subject/2"}
            ]}
            """);
        resolver = new WebSearchContentDomainResolver(search, new ObjectMapper());

        assertEquals(ContentDomainResolution.Domain.BOTH,
            resolver.resolve("三体").domain());
    }

    @Test
    void detectsVideoOnlyTitle() throws Exception {
        WebSearchTool search = mock(WebSearchTool.class);
        when(search.execute(any(JsonNode.class))).thenReturn("""
            {"success":true,"results":[
              {"title":"某动画番剧","snippet":"B站动漫，共12集","url":"https://www.bilibili.com/bangumi/play/ss1"}
            ]}
            """);
        resolver = new WebSearchContentDomainResolver(search, new ObjectMapper());

        assertEquals(ContentDomainResolution.Domain.BILIBILI,
            resolver.resolve("某作品").domain());
    }
}
