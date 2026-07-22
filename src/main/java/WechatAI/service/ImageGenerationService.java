package WechatAI.service;

/**
 * 图片生成服务抽象，输入自然语言提示词，输出可发送的图片二进制。
 */
public interface ImageGenerationService {

    byte[] generate(String prompt);

    String getLastErrorMessage();
}
