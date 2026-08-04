package com.clawbot.wechatbot.feature.excel.service;

import com.clawbot.wechatbot.feature.excel.model.ExcelAuditLog;
import com.clawbot.wechatbot.feature.excel.repository.ExcelAuditLogRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 操作审计服务测试：写入、detail 截断、按用户保留 200 条清理、列表。 */
class ExcelAuditServiceTests {

    @Test
    void recordPersistsLogWithTruncatedDetail() {
        ExcelAuditLogRepository repository = mock(ExcelAuditLogRepository.class);
        when(repository.countByWechatUserId(anyString())).thenReturn(1L);
        ExcelAuditService service = new ExcelAuditService(repository);

        String longDetail = "很长的结果文案".repeat(30); // 150 字
        service.record("user-1", "wb-1", "SORT+GROUP_SUMMARY", true, longDetail);

        ArgumentCaptor<ExcelAuditLog> captor = ArgumentCaptor.forClass(ExcelAuditLog.class);
        verify(repository).save(captor.capture());
        ExcelAuditLog saved = captor.getValue();
        assertEquals("user-1", saved.getWechatUserId());
        assertEquals("wb-1", saved.getWorkbookId());
        assertEquals("SORT+GROUP_SUMMARY", saved.getOperation());
        assertTrue(saved.isSuccess());
        // detail 超长截断 200 字，其余字段原样保留
        assertEquals(200, saved.getDetail().length());
        assertEquals(longDetail.substring(0, 200), saved.getDetail());
    }

    @Test
    void recordKeepsDetailWhenWithinLimit() {
        ExcelAuditLogRepository repository = mock(ExcelAuditLogRepository.class);
        when(repository.countByWechatUserId(anyString())).thenReturn(1L);
        ExcelAuditService service = new ExcelAuditService(repository);

        service.record("user-1", null, "QUERY", false, "❌ 找不到列「xx」");

        ArgumentCaptor<ExcelAuditLog> captor = ArgumentCaptor.forClass(ExcelAuditLog.class);
        verify(repository).save(captor.capture());
        assertEquals("❌ 找不到列「xx」", captor.getValue().getDetail());
        assertFalse(captor.getValue().isSuccess());
    }

    @Test
    void recordWithBlankUserIsSkipped() {
        ExcelAuditLogRepository repository = mock(ExcelAuditLogRepository.class);
        ExcelAuditService service = new ExcelAuditService(repository);

        service.record(null, "wb-1", "SORT", true, "已排序");
        service.record("  ", "wb-1", "SORT", true, "已排序");

        verify(repository, never()).save(any());
    }

    @Test
    void recordDeletesOldestBeyond200PerUser() {
        ExcelAuditLogRepository repository = mock(ExcelAuditLogRepository.class);
        // 已有 205 条：第 201..205 条（列表尾部 5 条最旧）应被清理
        List<ExcelAuditLog> existing = new ArrayList<>();
        for (int i = 0; i < 205; i++) {
            ExcelAuditLog log = new ExcelAuditLog("user-1", null, "SORT", true, "第" + i + "条");
            log.setId("log-" + i);
            existing.add(log);
        }
        when(repository.countByWechatUserId("user-1")).thenReturn(206L); // 写入后总数
        when(repository.findByWechatUserIdOrderByCreatedAtDesc("user-1")).thenReturn(existing);
        ExcelAuditService service = new ExcelAuditService(repository);

        service.record("user-1", null, "SORT", true, "新的一条");

        ArgumentCaptor<List<ExcelAuditLog>> captor = ArgumentCaptor.forClass(List.class);
        verify(repository).deleteAll(captor.capture());
        assertEquals(5, captor.getValue().size());
        // 删除的是最旧的 5 条（倒序列表尾部）
        assertEquals("log-200", captor.getValue().get(0).getId());
        assertEquals("log-204", captor.getValue().get(4).getId());
    }

    @Test
    void recordKeepsAllWhenUnderLimit() {
        ExcelAuditLogRepository repository = mock(ExcelAuditLogRepository.class);
        when(repository.countByWechatUserId("user-1")).thenReturn(150L);
        ExcelAuditService service = new ExcelAuditService(repository);

        service.record("user-1", null, "SORT", true, "已排序");

        verify(repository, never()).deleteAll(any());
        verify(repository, never()).deleteByWechatUserIdAndCreatedAtBefore(anyString(), any());
    }

    @Test
    void listReturnsRecentLogsWithLimit() {
        ExcelAuditLogRepository repository = mock(ExcelAuditLogRepository.class);
        List<ExcelAuditLog> logs = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            logs.add(new ExcelAuditLog("user-1", null, "SORT", true, "第" + i + "条"));
        }
        when(repository.findByWechatUserIdOrderByCreatedAtDesc("user-1")).thenReturn(logs);
        ExcelAuditService service = new ExcelAuditService(repository);

        List<ExcelAuditLog> recent = service.list("user-1", 3);

        assertEquals(3, recent.size());
        assertEquals("第0条", recent.get(0).getDetail());
    }

    @Test
    void listWithBlankUserReturnsEmpty() {
        ExcelAuditLogRepository repository = mock(ExcelAuditLogRepository.class);
        ExcelAuditService service = new ExcelAuditService(repository);

        assertTrue(service.list(null, 10).isEmpty());
        assertTrue(service.list("user-1", 0).isEmpty());
        verify(repository, never()).findByWechatUserIdOrderByCreatedAtDesc(anyString());
    }
}
