package com.clawbot.wechatbot.feature.excel.plan;

import com.clawbot.wechatbot.feature.excel.model.ExcelTable;

/** 单个操作类型的执行器：只负责一种操作；依赖（ExcelService）通过构造器注入。 */
public interface ExcelOperationHandler {

    /** 本处理器对应的操作类型。 */
    ExcelOperationType type();

    /** 执行单个操作，返回统一结果类型（attachment 可空）。 */
    OperationResult handle(String userId, ExcelOperation operation, ExcelTable table)
        throws Exception;
}
