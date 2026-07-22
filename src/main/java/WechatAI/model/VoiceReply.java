package WechatAI.model;

/**
 * 语音生成结果，封装可发送给微信的音频二进制和文件元信息。
 */
public class VoiceReply {

    private final byte[] audioBytes;
    private final String fileName;
    private final String mimeType;

    public VoiceReply(byte[] audioBytes, String fileName, String mimeType) {
        this.audioBytes = audioBytes;
        this.fileName = fileName;
        this.mimeType = mimeType;
    }

    public byte[] getAudioBytes() {
        return audioBytes;
    }

    public String getFileName() {
        return fileName;
    }

    public String getMimeType() {
        return mimeType;
    }
}
