package com.clawbot.wechatbot.feature.excel;

import com.clawbot.wechatbot.feature.excel.model.ExcelTable;
import com.clawbot.wechatbot.feature.excel.model.ExcelTableVersion;
import com.clawbot.wechatbot.feature.excel.model.ExcelUserState;
import com.clawbot.wechatbot.feature.excel.repository.ExcelTableRepository;
import com.clawbot.wechatbot.feature.excel.repository.ExcelTableVersionRepository;
import com.clawbot.wechatbot.feature.excel.repository.ExcelUserStateRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 多工作簿管理测试：活动表读写、新建/列表/归属校验/重命名/级联删除/复制。 */
class ExcelWorkbookServiceTests {

    private final ExcelTableRepository tableRepository = mock(ExcelTableRepository.class);
    private final ExcelTableVersionRepository versionRepository =
        mock(ExcelTableVersionRepository.class);
    private final ExcelUserStateRepository stateRepository =
        mock(ExcelUserStateRepository.class);
    private final ExcelService service =
        new ExcelService(tableRepository, versionRepository, stateRepository);

    private ExcelTable table(String id, String userId, String title) {
        ExcelTable table = new ExcelTable(userId, title);
        table.setId(id);
        return table;
    }

    // ============================
    // 活动表读写（getActiveWorkbook / setActiveWorkbook）
    // ============================
    @Test
    void backfillSetsEarliestTableAsActiveForUsersWithoutState() {
        ExcelTable first = table("t1", "user-1", "旧表");
        first.setCreatedAt(Instant.parse("2026-01-01T00:00:00Z"));
        ExcelTable second = table("t2", "user-1", "新表");
        second.setCreatedAt(Instant.parse("2026-02-01T00:00:00Z"));
        ExcelTable otherUser = table("t3", "user-2", "别人的表");
        otherUser.setCreatedAt(Instant.parse("2026-03-01T00:00:00Z"));
        when(stateRepository.findAll()).thenReturn(List.of());
        when(tableRepository.findAll()).thenReturn(List.of(first, otherUser, second));
        when(stateRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.backfillActiveWorkbooks();

        ArgumentCaptor<ExcelUserState> captor = ArgumentCaptor.forClass(ExcelUserState.class);
        verify(stateRepository, times(2)).save(captor.capture());
        // user-1 取最早表 t1（不是 t2）；user-2 取 t3
        assertEquals("t1", captor.getAllValues().get(0).getActiveWorkbookId());
        assertEquals("t3", captor.getAllValues().get(1).getActiveWorkbookId());
    }

    @Test
    void getActiveWorkbookReturnsNullWithoutState() {
        when(stateRepository.findByWechatUserId("user-1")).thenReturn(Optional.empty());
        assertNull(service.getActiveWorkbook("user-1"));
    }

    @Test
    void getActiveWorkbookReturnsActiveTable() {
        ExcelTable table = table("t1", "user-1", "销售表");
        ExcelUserState state = new ExcelUserState("user-1");
        state.setActiveWorkbookId("t1");
        when(stateRepository.findByWechatUserId("user-1")).thenReturn(Optional.of(state));
        when(tableRepository.findById("t1")).thenReturn(Optional.of(table));

        assertEquals(table, service.getActiveWorkbook("user-1"));
    }

    @Test
    void getActiveWorkbookReturnsNullWhenActiveTableMissing() {
        ExcelUserState state = new ExcelUserState("user-1");
        state.setActiveWorkbookId("t1");
        when(stateRepository.findByWechatUserId("user-1")).thenReturn(Optional.of(state));
        when(tableRepository.findById("t1")).thenReturn(Optional.empty());

        assertNull(service.getActiveWorkbook("user-1"));
    }

    @Test
    void setActiveWorkbookCreatesStateWhenMissing() {
        ExcelTable table = table("t1", "user-1", "销售表");
        when(stateRepository.findByWechatUserId("user-1")).thenReturn(Optional.empty());
        when(stateRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.setActiveWorkbook("user-1", table);

        ArgumentCaptor<ExcelUserState> captor = ArgumentCaptor.forClass(ExcelUserState.class);
        verify(stateRepository).save(captor.capture());
        assertEquals("user-1", captor.getValue().getWechatUserId());
        assertEquals("t1", captor.getValue().getActiveWorkbookId());
    }

    @Test
    void setActiveWorkbookUpdatesExistingState() {
        ExcelTable table = table("t2", "user-1", "周报");
        ExcelUserState state = new ExcelUserState("user-1");
        state.setActiveWorkbookId("t1");
        when(stateRepository.findByWechatUserId("user-1")).thenReturn(Optional.of(state));
        when(stateRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.setActiveWorkbook("user-1", table);

        assertEquals("t2", state.getActiveWorkbookId());
        verify(stateRepository).save(state);
    }

    // ============================
    // 新建 / 列表 / 归属校验
    // ============================
    @Test
    void createWorkbookSavesAndActivates() {
        when(tableRepository.save(any())).thenAnswer(inv -> {
            ExcelTable saved = inv.getArgument(0);
            saved.setId("t1");
            return saved;
        });
        when(stateRepository.findByWechatUserId("user-1")).thenReturn(Optional.empty());
        when(stateRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ExcelTable created = service.createWorkbook("user-1", "销售表");

        assertEquals("销售表", created.getTitle());
        assertEquals("user-1", created.getWechatUserId());
        verify(tableRepository).save(created);
        ArgumentCaptor<ExcelUserState> captor = ArgumentCaptor.forClass(ExcelUserState.class);
        verify(stateRepository).save(captor.capture());
        assertEquals("t1", captor.getValue().getActiveWorkbookId());
    }

    @Test
    void listWorkbooksReturnsCreationOrder() {
        ExcelTable older = table("t1", "user-1", "旧表");
        older.setCreatedAt(Instant.parse("2024-01-01T00:00:00Z"));
        ExcelTable newer = table("t2", "user-1", "新表");
        newer.setCreatedAt(Instant.parse("2024-02-01T00:00:00Z"));
        when(tableRepository.findByWechatUserId("user-1")).thenReturn(List.of(newer, older));

        List<ExcelTable> tables = service.listWorkbooks("user-1");

        assertEquals(List.of("旧表", "新表"),
            tables.stream().map(ExcelTable::getTitle).toList());
    }

    @Test
    void findWorkbookByIdReturnsNullForOtherUsersTable() {
        ExcelTable table = table("t1", "user-1", "销售表");
        when(tableRepository.findById("t1")).thenReturn(Optional.of(table));

        assertNull(service.findWorkbookById("user-2", "t1"));
        assertNull(service.findWorkbookById("user-1", "missing"));
    }

    @Test
    void renameWorkbookRenamesOwnedTable() {
        ExcelTable table = table("t1", "user-1", "销售表");
        when(tableRepository.findById("t1")).thenReturn(Optional.of(table));
        when(tableRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Optional<ExcelTable> renamed = service.renameWorkbook("user-1", "t1", "月度销售");

        assertTrue(renamed.isPresent());
        assertEquals("月度销售", renamed.get().getTitle());
        // 归属校验：不是该用户的表不能改名
        assertEquals(Optional.empty(), service.renameWorkbook("user-2", "t1", "x"));
    }

    // ============================
    // 删除（级联删版本、活动状态置空）
    // ============================
    @Test
    void deleteWorkbookCascadesVersionsAndClearsActiveState() {
        ExcelTable table = table("t1", "user-1", "销售表");
        ExcelUserState state = new ExcelUserState("user-1");
        state.setActiveWorkbookId("t1");
        ExcelTableVersion v1 =
            new ExcelTableVersion("t1", List.of(), List.of(), "添加第1行");
        when(tableRepository.findById("t1")).thenReturn(Optional.of(table));
        when(versionRepository.findByTableIdOrderByCreatedAtDesc("t1"))
            .thenReturn(List.of(v1));
        when(stateRepository.findByWechatUserId("user-1")).thenReturn(Optional.of(state));
        when(stateRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        boolean deleted = service.deleteWorkbook("user-1", "t1");

        assertTrue(deleted);
        verify(tableRepository).delete(table);
        verify(versionRepository).deleteAll(List.of(v1));
        // 删除的是活动表：活动状态置空
        assertNull(state.getActiveWorkbookId());
        verify(stateRepository).save(state);
    }

    @Test
    void deleteWorkbookKeepsActiveStateWhenDeletingOtherTable() {
        ExcelTable table = table("t2", "user-1", "周报");
        ExcelUserState state = new ExcelUserState("user-1");
        state.setActiveWorkbookId("t1");
        when(tableRepository.findById("t2")).thenReturn(Optional.of(table));
        when(versionRepository.findByTableIdOrderByCreatedAtDesc("t2")).thenReturn(List.of());
        when(stateRepository.findByWechatUserId("user-1")).thenReturn(Optional.of(state));

        assertTrue(service.deleteWorkbook("user-1", "t2"));

        assertEquals("t1", state.getActiveWorkbookId());
        verify(stateRepository, never()).save(any());
    }

    @Test
    void deleteWorkbookReturnsFalseForOtherUsersTable() {
        ExcelTable table = table("t1", "user-1", "销售表");
        when(tableRepository.findById("t1")).thenReturn(Optional.of(table));

        assertFalse(service.deleteWorkbook("user-2", "t1"));

        verify(tableRepository, never()).delete(any());
        verify(versionRepository, never()).deleteAll(any());
    }

    // ============================
    // 复制（新 id、标题加副本、不含版本历史、设为活动表）
    // ============================
    @Test
    void copyWorkbookCopiesDataWithoutVersionsAndActivates() {
        ExcelTable table = table("t1", "user-1", "销售表");
        table.setHeaders(List.of("姓名", "城市"));
        table.setRows(List.of(List.of("张三", "北京")));
        when(tableRepository.findById("t1")).thenReturn(Optional.of(table));
        when(tableRepository.save(any())).thenAnswer(inv -> {
            ExcelTable saved = inv.getArgument(0);
            saved.setId("t2");
            return saved;
        });
        when(stateRepository.findByWechatUserId("user-1")).thenReturn(Optional.empty());
        when(stateRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Optional<ExcelTable> copied = service.copyWorkbook("user-1", "t1");

        assertTrue(copied.isPresent());
        ExcelTable copy = copied.get();
        assertNotEquals("t1", copy.getId());
        assertEquals("销售表副本", copy.getTitle());
        assertEquals(List.of("姓名", "城市"), copy.getHeaders());
        assertEquals(List.of(List.of("张三", "北京")), copy.getRows());
        // 复制不含版本历史：不查询也不删除版本
        verify(versionRepository, never()).findByTableIdOrderByCreatedAtDesc(anyString());
        // 复制后设为活动表
        ArgumentCaptor<ExcelUserState> captor = ArgumentCaptor.forClass(ExcelUserState.class);
        verify(stateRepository).save(captor.capture());
        assertEquals("t2", captor.getValue().getActiveWorkbookId());
    }

    @Test
    void copyWorkbookReturnsEmptyForOtherUsersTable() {
        ExcelTable table = table("t1", "user-1", "销售表");
        when(tableRepository.findById("t1")).thenReturn(Optional.of(table));

        assertEquals(Optional.empty(), service.copyWorkbook("user-2", "t1"));

        verify(tableRepository, never()).save(any());
    }
}
