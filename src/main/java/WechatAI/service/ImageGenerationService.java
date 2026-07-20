package WechatAI.service;

public interface ImageGenerationService {

    byte[] generate(String prompt);

    String getLastErrorMessage();
}
