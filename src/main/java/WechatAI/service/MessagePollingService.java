package WechatAI.service;

import com.github.wechat.ilink.sdk.ILinkClient;
import com.github.wechat.ilink.sdk.core.model.WeixinMessage;

import java.io.IOException;
import java.util.List;

public class MessagePollingService {

    private static final long EMPTY_POLL_INTERVAL_MS = 1000L;
    private static final long IO_ERROR_RETRY_INTERVAL_MS = 5000L;
    private static final long UNKNOWN_ERROR_RETRY_INTERVAL_MS = 3000L;

    private final ILinkClient client;
    private final WechatMessageService messageService;
    private volatile boolean running = true;

    public MessagePollingService(ILinkClient client, WechatMessageService messageService) {
        this.client = client;
        this.messageService = messageService;
    }

    public void start() {
        Thread pollingThread = new Thread(this::poll, "wechat-message-polling");
        pollingThread.setDaemon(false);
        pollingThread.start();
    }

    public void stop() {
        running = false;
    }

    private void poll() {
        while (running) {
            try {
                List<WeixinMessage> messages = client.getUpdates();
                if (messages != null && !messages.isEmpty()) {
                    for (WeixinMessage message : messages) {
                        messageService.handleAndReply(message);
                    }
                }
                sleep(EMPTY_POLL_INTERVAL_MS);
            } catch (IOException e) {
                System.err.println("⚠️ 拉取消息异常: " + e.getMessage());
                pause(IO_ERROR_RETRY_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                System.out.println("消息轮询已停止");
                break;
            } catch (Exception e) {
                System.err.println("⚠️ 未知异常: " + e.getMessage());
                e.printStackTrace();
                pause(UNKNOWN_ERROR_RETRY_INTERVAL_MS);
            }
        }
    }

    private void sleep(long millis) throws InterruptedException {
        Thread.sleep(millis);
    }

    private void pause(long millis) {
        try {
            sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            running = false;
        }
    }
}
