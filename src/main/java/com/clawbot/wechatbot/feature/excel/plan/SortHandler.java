package com.clawbot.wechatbot.feature.excel.plan;

import com.clawbot.wechatbot.feature.excel.ExcelService;
import com.clawbot.wechatbot.feature.excel.model.ExcelTable;

import java.util.ArrayList;
import java.util.List;

/**
 * 排序操作：按列排序——能解析为数字的按数值比较，其余按字符串 compareTo（中文按 Unicode）；
 * 空值恒排最后；direction 控制升降序（List.sort 为稳定排序，相同值保持原顺序）。
 */
public final class SortHandler implements ExcelOperationHandler {

    private final ExcelService excelService;

    public SortHandler(ExcelService excelService) {
        this.excelService = excelService;
    }

    @Override
    public ExcelOperationType type() {
        return ExcelOperationType.SORT;
    }

    @Override
    public OperationResult handle(String userId, ExcelOperation operation, ExcelTable table)
        throws Exception {
        OperationChecks.requireTable(table);
        String column = operation.param("column");
        int columnIndex = ExcelService.findColumnIndex(table.getHeaders(), column);
        if (columnIndex < 0) {
            return OperationResult.failure(
                "❌ 找不到列「" + column + "」，现有列：" + String.join("、", table.getHeaders()));
        }
        boolean descending = "DESC".equals(operation.param("direction"));
        // 变更前快照，便于回滚
        excelService.snapshotVersion(table, "按" + column + "排序");
        List<List<String>> sorted = new ArrayList<>(table.getRows());
        // 稳定排序：相同值保持原顺序
        sorted.sort((a, b) -> compare(a, b, columnIndex, descending));
        table.setRows(sorted);
        // 先导出再保存：导出失败（如公式错误）时不落库
        byte[] bytes = excelService.toXlsx(table);
        excelService.save(table);
        return OperationResult.success(
            "✅ 已按" + column + "排序（" + (descending ? "降序" : "升序") + "）。", bytes);
    }

    /** 比较两行的排序列：空值恒排最后（不受方向影响）；数值优先，其余按字符串比较。 */
    private static int compare(List<String> a, List<String> b, int columnIndex, boolean descending) {
        String va = value(a, columnIndex);
        String vb = value(b, columnIndex);
        boolean aEmpty = va == null || va.isEmpty();
        boolean bEmpty = vb == null || vb.isEmpty();
        if (aEmpty || bEmpty) {
            // 空值恒排最后，不受方向影响（不能对空值判断做方向翻转）
            if (aEmpty && bEmpty) return 0;
            return aEmpty ? 1 : -1;
        }
        Double aNum = ExcelService.parseNumber(va);
        Double bNum = ExcelService.parseNumber(vb);
        int cmp;
        if (aNum != null && bNum != null) {
            cmp = aNum.compareTo(bNum);
        } else if (aNum != null) {
            cmp = -1; // 数值优先
        } else if (bNum != null) {
            cmp = 1;
        } else {
            cmp = va.compareTo(vb);
        }
        return descending ? -cmp : cmp;
    }

    private static String value(List<String> cells, int columnIndex) {
        return columnIndex < cells.size() ? cells.get(columnIndex) : "";
    }
}
