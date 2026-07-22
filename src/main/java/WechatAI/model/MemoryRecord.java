package WechatAI.model;

/**
 * Redis 短期记忆记录，额外保存写入时间便于后续扩展清理和分析。
 */
public class MemoryRecord {

    private String role;
    private String content;
    private long timestamp;

    public MemoryRecord() {
    }

    public MemoryRecord(String role, String content, long timestamp) {
        this.role = role;
        this.content = content;
        this.timestamp = timestamp;
    }

    public String getRole() {
        return role;
    }

    public String getContent() {
        return content;
    }

    public long getTimestamp() {
        return timestamp;
    }
}
