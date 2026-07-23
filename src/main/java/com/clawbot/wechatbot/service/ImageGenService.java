package com.clawbot.wechatbot.service;

/**
 * 文生图服务 —— 接口层
 */
public interface ImageGenService {

    /**
     * 根据文本描述生成图片
     * @param prompt  图片描述（中文/英文均可）
     * @return        图片文件字节（PNG 格式）
     */
    byte[] generateImage(String prompt) throws Exception;

    boolean isConfigured();
}