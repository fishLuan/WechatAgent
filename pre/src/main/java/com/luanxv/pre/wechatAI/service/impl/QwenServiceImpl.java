package com.luanxv.pre.wechatAI.service.impl;

import com.luanxv.pre.wechatAI.service.QwenService;
import org.springframework.stereotype.Service;

@Service
public class QwenServiceImpl implements QwenService {
    private final QwenChatService chatService;
    private final QwenVLService vlService;
    private final QwenImageGenService imageGenService;
    private final QwenSpeechService speechService;

    public QwenServiceImpl(QwenChatService chatService, QwenVLService vlService,
                           QwenImageGenService imageGenService, QwenSpeechService speechService) {
        this.chatService = chatService;
        this.vlService = vlService;
        this.imageGenService = imageGenService;
        this.speechService = speechService;
    }

    @Override
    public String chat(String userId, String userMessage) {
        return chatService.chat(userId, userMessage);
    }

    @Override
    public String chat(String userId, String userMessage, boolean forceWebSearch) {
        return chatService.chat(userId, userMessage, forceWebSearch);
    }

    @Override
    public String chatDocument(String userId, String documentPrompt, String memorySummary) {
        return chatService.chatDocument(userId, documentPrompt, memorySummary);
    }

    @Override
    public String recognizeImage(String userId, byte[] imageBytes, String userQuestion) {
        return vlService.recognizeImage(userId, imageBytes, userQuestion);
    }

    @Override
    public byte[] generateImage(String prompt) {
        return imageGenService.generateImage(prompt);
    }

    @Override
    public byte[] editImage(byte[] originalImage, String instruction) {
        return imageGenService.editImage(originalImage, instruction);
    }

    @Override
    public String recognizeSpeech(byte[] audioBytes) {
        return speechService.recognizeSpeech(audioBytes);
    }

    @Override
    public byte[] synthesizeSpeech(String text, String voice, String model) {
        return speechService.synthesizeSpeech(text, voice, model);
    }
}
