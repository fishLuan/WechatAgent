package WechatAI.service.impl;

import WechatAI.config.AiProperties;
import WechatAI.model.ChatMessage;
import WechatAI.model.MemoryRecord;
import WechatAI.service.MemoryService;
import com.google.gson.Gson;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Redis 短期记忆实现，按用户维度保存最近多轮对话。
 */
public class RedisMemoryService implements MemoryService {

    private static final String KEY_PREFIX = "wechat:memory:";

    private final AiProperties properties;
    private final Gson gson;
    private final JedisPool jedisPool;

    public RedisMemoryService(AiProperties properties) {
        this(properties, new Gson(), createPool(properties));
    }

    public RedisMemoryService(AiProperties properties, Gson gson, JedisPool jedisPool) {
        this.properties = properties;
        this.gson = gson;
        this.jedisPool = jedisPool;
    }

    @Override
    public List<ChatMessage> loadRecentMessages(String userId) {
        if (!properties.isMemoryEnabled()) {
            return Collections.emptyList();
        }

        try (Jedis jedis = jedisPool.getResource()) {
            List<String> values = jedis.lrange(memoryKey(userId), 0, -1);
            List<ChatMessage> messages = new ArrayList<>();
            for (String value : values) {
                MemoryRecord record = gson.fromJson(value, MemoryRecord.class);
                if (record != null && hasText(record.getRole()) && hasText(record.getContent())) {
                    messages.add(new ChatMessage(record.getRole(), record.getContent()));
                }
            }
            return messages;
        } catch (Exception e) {
            System.err.println("⚠️ 读取 Redis 短期记忆失败，降级为无记忆聊天: " + e.getMessage());
            return Collections.emptyList();
        }
    }

    @Override
    public void appendConversation(String userId, String userMessage, String assistantMessage) {
        if (!properties.isMemoryEnabled() || !hasText(userMessage) || !hasText(assistantMessage)) {
            return;
        }

        try (Jedis jedis = jedisPool.getResource()) {
            String key = memoryKey(userId);
            jedis.rpush(
                    key,
                    gson.toJson(new MemoryRecord("user", userMessage, System.currentTimeMillis())),
                    gson.toJson(new MemoryRecord("assistant", assistantMessage, System.currentTimeMillis()))
            );
            jedis.ltrim(key, -properties.getMemoryMaxMessages(), -1);
            jedis.expire(key, properties.getMemoryTtlSeconds());
            System.out.println("🧠 已写入短期记忆: user=" + userId
                    + ", max=" + properties.getMemoryMaxMessages()
                    + ", ttl=" + properties.getMemoryTtlSeconds());
        } catch (Exception e) {
            System.err.println("⚠️ 写入 Redis 短期记忆失败，已忽略: " + e.getMessage());
        }
    }

    @Override
    public void clear(String userId) {
        try (Jedis jedis = jedisPool.getResource()) {
            jedis.del(memoryKey(userId));
            System.out.println("🧠 已清空短期记忆: user=" + userId);
        } catch (Exception e) {
            System.err.println("⚠️ 清空 Redis 短期记忆失败: " + e.getMessage());
        }
    }

    private String memoryKey(String userId) {
        return KEY_PREFIX + userId;
    }

    private static JedisPool createPool(AiProperties properties) {
        JedisPoolConfig poolConfig = new JedisPoolConfig();
        poolConfig.setMaxTotal(16);
        poolConfig.setMaxIdle(8);
        poolConfig.setMinIdle(1);

        String password = normalizePassword(properties.getRedisPassword());
        if (password.isEmpty()) {
            return new JedisPool(poolConfig, properties.getRedisHost(), properties.getRedisPort(), 2000, null, properties.getRedisDatabase());
        }
        return new JedisPool(poolConfig, properties.getRedisHost(), properties.getRedisPort(), 2000, password, properties.getRedisDatabase());
    }

    private static String normalizePassword(String password) {
        if (password == null || password.trim().isEmpty() || password.contains("${")) {
            return "";
        }
        return password.trim();
    }

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
