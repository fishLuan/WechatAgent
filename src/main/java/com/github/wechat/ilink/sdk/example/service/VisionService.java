package com.github.wechat.ilink.sdk.example.service;

/**
 * 视觉服务 —— 接口层
 * 看图（图片理解）
 */
public interface VisionService {

    /**
     * 传入图片字节 + 问题描述，返回 AI 对图片内容的理解
     * @param imageBytes  图片字节（PNG/JPEG 等）
     * @param question    关于图片的问题（比如"这张图里有什么？"），空的话默认描述图片
     * @return            图片内容的文字描述 / 回答
     */
    String understandImage(byte[] imageBytes, String question) throws Exception;

    boolean isConfigured();
}