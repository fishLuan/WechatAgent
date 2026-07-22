package WechatAI.service;

/**
 * 微信消息轮询服务抽象，负责生命周期控制。
 */
public interface MessagePollingService {

    void start();

    void stop();
}
