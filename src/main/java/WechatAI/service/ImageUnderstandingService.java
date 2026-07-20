package WechatAI.service;

public interface ImageUnderstandingService {

    String understand(byte[] imageBytes, String prompt);
}
