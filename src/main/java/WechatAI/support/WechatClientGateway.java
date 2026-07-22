package WechatAI.support;

import com.github.wechat.ilink.sdk.ILinkClient;
import com.github.wechat.ilink.sdk.core.model.MessageItem;
import com.github.wechat.ilink.sdk.core.model.WeixinMessage;

import java.io.IOException;
import java.util.List;

/**
 * 微信 SDK 访问网关，集中串行化 ILinkClient 调用，避免多线程并发访问 SDK 导致状态不一致。
 */
public class WechatClientGateway {

    private final ILinkClient client;
    private final Object lock = new Object();

    public WechatClientGateway(ILinkClient client) {
        this.client = client;
    }

    /**
     * 拉取微信增量消息；所有调用都经过同一把锁，保证与发送/下载操作互斥。
     */
    public List<WeixinMessage> getUpdates() throws IOException {
        synchronized (lock) {
            return client.getUpdates();
        }
    }

    public void sendText(String fromUserId, String text) throws IOException {
        synchronized (lock) {
            client.sendText(fromUserId, text);
        }
    }

    public void sendImage(String fromUserId, byte[] imageBytes, String fileName, String mimeType) throws IOException {
        synchronized (lock) {
            client.sendImage(fromUserId, imageBytes, fileName, mimeType);
        }
    }

    public void sendFile(String fromUserId, byte[] fileBytes, String fileName, String mimeType) throws IOException {
        synchronized (lock) {
            client.sendFile(fromUserId, fileBytes, fileName, mimeType);
        }
    }

    public void startTyping(String fromUserId) throws IOException {
        synchronized (lock) {
            client.startTyping(fromUserId);
        }
    }

    public void stopTyping(String fromUserId) throws IOException {
        synchronized (lock) {
            client.stopTyping(fromUserId);
        }
    }

    public byte[] downloadImage(MessageItem imageItem) throws IOException {
        synchronized (lock) {
            return client.downloadImageFromMessageItem(imageItem);
        }
    }

    public byte[] downloadVoice(MessageItem voiceItem) throws IOException {
        synchronized (lock) {
            return client.downloadVoiceFromMessageItem(voiceItem);
        }
    }

    public byte[] downloadFile(MessageItem fileItem) throws IOException {
        synchronized (lock) {
            return client.downloadFileFromMessageItem(fileItem);
        }
    }
}
