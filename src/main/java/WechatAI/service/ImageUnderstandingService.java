package WechatAI.service;

/**
 * 图片理解服务抽象，负责把图片内容和用户提示转换成文本回复。
 */
public interface ImageUnderstandingService {

    String understand(byte[] imageBytes, String prompt);
}
