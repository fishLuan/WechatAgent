package com.clawbot.wechatbot.memory;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Sort.Direction;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;

@Service
@ConditionalOnProperty(
    name = "clawbot.memory.enabled",
    havingValue = "true",
    matchIfMissing = true
)
public class MongoConversationMemoryService implements ConversationMemoryService {
    private final MongoTemplate mongoTemplate;
    private final MemoryProperties properties;
    private final MemoryKeyFactory keys;

    public MongoConversationMemoryService(
        MongoTemplate mongoTemplate,
        MemoryProperties properties,
        MemoryKeyFactory keys
    ) {
        this.mongoTemplate = mongoTemplate;
        this.properties = properties;
        this.keys = keys;
    }

    @PostConstruct
    void initializeIndexesAndVerifyConnection() {
        mongoTemplate.executeCommand("{ ping: 1 }");
        mongoTemplate.indexOps(ConversationMemory.class).ensureIndex(
            new Index()
                .on("namespace", Direction.ASC)
                .on("userKey", Direction.ASC)
                .unique()
                .named("uk_conversation_memory_namespace_user")
        );
        mongoTemplate.indexOps(ProcessedMessage.class).ensureIndex(
            new Index()
                .on("expireAt", Direction.ASC)
                .expire(Duration.ZERO)
                .named("ttl_processed_message_expire_at")
        );
    }

    @Override
    public ConversationMemory get(String userId) {
        String id = keys.memoryId(userId);
        ConversationMemory memory = mongoTemplate.findById(id, ConversationMemory.class);
        return memory == null
            ? ConversationMemory.empty(id, keys.namespace(), keys.userKey(userId))
            : memory;
    }

    @Override
    public ConversationMemory appendTurn(
        String userId, String userText, String assistantReply
    ) {
        Instant now = Instant.now();
        ConversationMessage userMessage = new ConversationMessage("user", userText, now);
        ConversationMessage assistantMessage =
            new ConversationMessage("assistant", assistantReply, now);
        Update update = new Update()
            .setOnInsert("namespace", keys.namespace())
            .setOnInsert("userKey", keys.userKey(userId))
            .setOnInsert("longTermSummary", "")
            .setOnInsert("createdAt", now)
            .set("updatedAt", now)
            .inc("turnCounter", 1);
        update.push("recentMessages")
            .slice(-(properties.getRecentTurns() * 2))
            .each(userMessage, assistantMessage);

        ConversationMemory updated = mongoTemplate.findAndModify(
            Query.query(Criteria.where("_id").is(keys.memoryId(userId))),
            update,
            FindAndModifyOptions.options().upsert(true).returnNew(true),
            ConversationMemory.class
        );
        if (updated == null) {
            throw new IllegalStateException(
                "MongoDB did not return the updated conversation memory");
        }
        return updated;
    }

    @Override
    public ConversationMemory replaceSummary(String userId, String summary) {
        Instant now = Instant.now();
        Update update = new Update()
            .setOnInsert("namespace", keys.namespace())
            .setOnInsert("userKey", keys.userKey(userId))
            .setOnInsert("createdAt", now)
            .set("longTermSummary", summary == null ? "" : summary.trim())
            .set("updatedAt", now);
        mongoTemplate.upsert(
            Query.query(Criteria.where("_id").is(keys.memoryId(userId))),
            update,
            ConversationMemory.class
        );
        return get(userId);
    }

    @Override
    public boolean markMessageProcessed(String userId, Long messageId) {
        Instant expireAt =
            Instant.now().plus(Duration.ofMinutes(properties.getMessageDedupTtlMinutes()));
        ProcessedMessage processed = new ProcessedMessage(
            keys.deduplicationId(userId, messageId),
            keys.namespace(),
            keys.userKey(userId),
            messageId,
            expireAt
        );
        try {
            mongoTemplate.insert(processed);
            return true;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    @Override
    public void clear(String userId) {
        String userKey = keys.userKey(userId);
        mongoTemplate.remove(
            Query.query(Criteria.where("_id").is(keys.memoryId(userId))),
            ConversationMemory.class
        );
        mongoTemplate.remove(
            Query.query(
                Criteria.where("namespace").is(keys.namespace()).and("userKey").is(userKey)
            ),
            ProcessedMessage.class
        );
    }
}
