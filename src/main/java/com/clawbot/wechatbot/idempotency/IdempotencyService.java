package com.clawbot.wechatbot.idempotency;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.PostConstruct;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;

@Service
public class IdempotencyService {
    private final MongoTemplate mongoTemplate;
    private final ObjectMapper mapper;

    public IdempotencyService(MongoTemplate mongoTemplate, ObjectMapper mapper) {
        this.mongoTemplate = mongoTemplate;
        this.mapper = mapper;
    }

    @PostConstruct
    void ensureIndexes() {
        mongoTemplate.indexOps(IdempotencyExecution.class).ensureIndex(
            new Index().on("idempotencyKey", Sort.Direction.ASC).unique());
    }

    public String key(String scope, String operation, JsonNode arguments) {
        try {
            String payload = safe(scope) + "\n" + safe(operation) + "\n"
                + mapper.writeValueAsString(canonical(arguments));
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(payload.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception exception) {
            throw new IllegalStateException("无法生成幂等键", exception);
        }
    }

    public IdempotencyClaim claim(String key, String operation) {
        IdempotencyExecution execution = new IdempotencyExecution();
        long now = System.currentTimeMillis();
        execution.setIdempotencyKey(key);
        execution.setOperation(operation);
        execution.setStatus(IdempotencyStatus.EXECUTING);
        execution.setCreatedAt(now);
        execution.setUpdatedAt(now);
        try {
            return new IdempotencyClaim(true, mongoTemplate.insert(execution));
        } catch (DuplicateKeyException duplicate) {
            IdempotencyExecution existing = find(key);
            if (existing != null && existing.getStatus() == IdempotencyStatus.FAILED) {
                Query failed = Query.query(Criteria.where("idempotencyKey").is(key)
                    .and("status").is(IdempotencyStatus.FAILED));
                Update retry = new Update().set("status", IdempotencyStatus.EXECUTING)
                    .set("error", "").set("updatedAt", now);
                IdempotencyExecution acquired = mongoTemplate.findAndModify(
                    failed, retry, FindAndModifyOptions.options().returnNew(true),
                    IdempotencyExecution.class);
                if (acquired != null) return new IdempotencyClaim(true, acquired);
            }
            return new IdempotencyClaim(false, existing);
        }
    }

    public IdempotencyExecution find(String key) {
        return mongoTemplate.findOne(
            Query.query(Criteria.where("idempotencyKey").is(key)),
            IdempotencyExecution.class);
    }

    public void succeed(String key, String result) {
        update(key, IdempotencyStatus.SUCCEEDED, result, "");
    }

    public void fail(String key, String error) {
        update(key, IdempotencyStatus.FAILED, "", error);
    }

    private void update(String key, IdempotencyStatus status, String result, String error) {
        IdempotencyExecution execution = find(key);
        if (execution == null) return;
        execution.setStatus(status);
        execution.setResult(result == null ? "" : result);
        execution.setError(error == null ? "" : error);
        execution.setUpdatedAt(System.currentTimeMillis());
        mongoTemplate.save(execution);
    }

    private JsonNode canonical(JsonNode node) {
        if (node == null || node.isNull()) return mapper.nullNode();
        if (node.isArray()) {
            ArrayNode array = mapper.createArrayNode();
            node.forEach(item -> array.add(canonical(item)));
            return array;
        }
        if (!node.isObject()) return node;
        ObjectNode object = mapper.createObjectNode();
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        names.sort(Comparator.naturalOrder());
        names.forEach(name -> object.set(name, canonical(node.get(name))));
        return object;
    }

    private String safe(String value) { return value == null ? "" : value.trim(); }
}
