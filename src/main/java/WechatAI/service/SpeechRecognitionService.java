package WechatAI.service;

/**
 * 语音识别服务抽象，负责把微信语音或音频文件转换成文本。
 */
public interface SpeechRecognitionService {

    String recognize(byte[] audioBytes, String fileName);

    String recognize(byte[] audioBytes, String fileName, String format, Integer sampleRate);

    String getLastErrorMessage();
}
