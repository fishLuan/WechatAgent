package com.Student.wechatbot.service;

/**
 * 文本对话服务 —— 接口层
 * 未来想换模型（如换成 GPT、通义千问），只要再写一个实现类即可
 */
public interface ChatService {

    /**
     * 对话：传入用户文本 + 历史消息，返回 AI 回复
     * @param userText  用户本次说的话
     * @param history   历史对话 JSON 字符串（DeepSeek messages 数组格式），可空
     * @return          AI 回复文本
     */
    String chat(String userText, String history) throws Exception;

    /**
     * 是否已配置 Key（方便 UI 层判断要不要给用户提示）
     */
    boolean isConfigured();
}