package WechatAI.service;

/**
 * 文档分析服务抽象，负责文档类型识别、文本抽取和摘要生成。
 */
public interface DocumentAnalysisService {

    boolean supports(String fileName);

    String analyze(byte[] fileBytes, String fileName, String prompt);

    String getLastErrorMessage();
}
