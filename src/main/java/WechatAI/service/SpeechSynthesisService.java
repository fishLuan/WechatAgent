package WechatAI.service;

import WechatAI.model.VoiceReply;

/**
 * 语音合成服务抽象，支持按音色动态生成可发送的音频文件。
 */
public interface SpeechSynthesisService {

    VoiceReply synthesize(String text);

    VoiceReply synthesize(String text, String voice);

    String getLastErrorMessage();
}
