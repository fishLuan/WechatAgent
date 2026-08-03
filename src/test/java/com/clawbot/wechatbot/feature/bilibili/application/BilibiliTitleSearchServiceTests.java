package com.clawbot.wechatbot.feature.bilibili.application;

import com.clawbot.wechatbot.feature.bilibili.config.BilibiliProperties;
import com.clawbot.wechatbot.feature.bilibili.model.BilibiliContent;
import com.clawbot.wechatbot.feature.bilibili.model.ContentType;
import com.clawbot.wechatbot.feature.bilibili.repository.BilibiliContentRepository;
import com.clawbot.wechatbot.feature.bilibili.source.BilibiliContentSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BilibiliTitleSearchServiceTests {
    private BilibiliContentRepository repository;
    private BilibiliContentSource remote;
    private BilibiliTitleSearchService service;

    @BeforeEach
    void setUp() {
        repository = mock(BilibiliContentRepository.class);
        remote = mock(BilibiliContentSource.class);
        service = new BilibiliTitleSearchService(
            repository, remote, new BilibiliProperties());
    }

    @Test
    void returnsLocalResultsWithoutCallingBilibili() throws Exception {
        BilibiliContent local = content("1", "魁拔之十万火急");
        when(repository.findByTitleContainingIgnoreCase(
            eq("魁拔"), any(Pageable.class))).thenReturn(List.of(local));

        List<BilibiliContent> results = service.search("魁拔");

        assertEquals(List.of(local), results);
        verify(remote, never()).searchByTitle(any(), anyInt());
    }

    @Test
    void cachesRemoteResultsAndWritesThemToLocalRepository() throws Exception {
        BilibiliContent remoteItem = content("2", "魁拔之殊途");
        when(repository.findByTitleContainingIgnoreCase(
            eq("魁拔"), any(Pageable.class))).thenReturn(List.of());
        when(remote.searchByTitle("魁拔", 5)).thenReturn(List.of(remoteItem));
        when(repository.findByContentTypeAndContentId(
            ContentType.BANGUMI, "2")).thenReturn(Optional.empty());

        assertEquals(1, service.search("魁拔").size());
        assertEquals(1, service.search("魁拔").size());

        verify(remote, times(1)).searchByTitle("魁拔", 5);
        verify(repository).save(remoteItem);
    }

    private BilibiliContent content(String id, String title) {
        return new BilibiliContent(ContentType.BANGUMI, id, title);
    }
}
