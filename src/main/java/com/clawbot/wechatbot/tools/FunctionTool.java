package com.clawbot.wechatbot.tools;

import com.fasterxml.jackson.databind.JsonNode;

/** 可被大模型通过 function-calling 调用的本地工具。 */
public interface FunctionTool {
    String name();

    /** OpenAI/DeepSeek tools 数组中单个工具的 JSON 定义。 */
    JsonNode definition();

    /** 执行模型传入的 arguments，并返回可直接回传给模型的文本。 */
    String execute(JsonNode arguments) throws Exception;
}
