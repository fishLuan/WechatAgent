package com.clawbot.wechatbot.service.agent;

/** 外层 Agent 循环可以调度的任务类型。 */
public enum AgentTaskType {
    /** 交给大模型及其 function-calling 内循环处理。 */
    CHAT_TOOL,
    /** 使用用户上传的图片回答问题。 */
    IMAGE_UNDERSTANDING,
    /** 提取并分析用户上传的 PDF、Word 或 TXT 文档。 */
    DOCUMENT_ANALYSIS,
    /** 交给图片生成服务处理，结果以附件返回。 */
    IMAGE_GENERATION
}
