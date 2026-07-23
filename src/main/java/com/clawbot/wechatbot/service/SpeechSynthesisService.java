package com.clawbot.wechatbot.service;

/**
 * 语音合成服务接口 —— 把文字转成音频文件（MP3/WAV）
 *
 * 注意：微信 iLink Bot 协议限制机器人无法主动发送语音气泡消息，
 * 因此这里生成的音频通过 sendFile 作为文件发送给用户。
 */
public interface SpeechSynthesisService {

    /** 把文字合成为音频（用默认音色） */
    byte[] synthesize(String text) throws Exception;

    /** 把文字合成为音频（指定音色：Cherry/Dora/Echo/Ivy 等） */
    byte[] synthesize(String text, String voice) throws Exception;

    /** 返回音频格式的文件扩展名（如 "mp3" / "wav"） */
    String getFileExtension();
}