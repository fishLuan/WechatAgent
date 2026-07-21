package com.luanxv.pre.wechatAI.service;

public interface QwenService {
    /**
     * 文本对话
     */
    String chat(String userId, String userMessage);

    String chat(String userId, String userMessage, boolean forceWebSearch);

    /** Answer from uploaded document content without persisting the full document in conversation memory. */
    String chatDocument(String userId, String documentPrompt, String memorySummary);

    /**
     * 图片识别 (VL)
     */
    String recognizeImage(String userId, byte[] imageBytes, String userQuestion);

    /**
     * 图片生成
     */
    byte[] generateImage(String prompt);

    byte[] editImage(byte[] originalImage, String instruction);

    /** Transcribe a WeChat voice message. */
    String recognizeSpeech(byte[] audioBytes);

    /** Generate speech using a voice and its matching model family. */
    byte[] synthesizeSpeech(String text, String voice, String model);
}
