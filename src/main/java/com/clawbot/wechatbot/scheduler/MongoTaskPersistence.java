package com.clawbot.wechatbot.scheduler;

import jakarta.annotation.PostConstruct;
import org.springframework.data.domain.Sort;
import org.springframework.data.domain.Sort.Direction;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

public class MongoTaskPersistence implements TaskPersistence {

    private final MongoTemplate mongoTemplate;
    private final TaskSchedulerProperties props;

    public MongoTaskPersistence(
        MongoTemplate mongoTemplate,
        TaskSchedulerProperties props
    ) {
        this.mongoTemplate = mongoTemplate;
        this.props = props;
    }

    @PostConstruct
    void initializeIndexesAndVerifyConnection() {
        mongoTemplate.executeCommand("{ ping: 1 }");
        mongoTemplate.indexOps(MongoScheduledTask.class).createIndex(
            new Index()
                .on("userId", Direction.ASC)
                .on("createdAt", Direction.DESC)
                .named("idx_task_user_created")
        );
        mongoTemplate.indexOps(MongoScheduledTask.class).createIndex(
            new Index()
                .on("status", Direction.ASC)
                .on("nextFireTime", Direction.ASC)
                .named("idx_task_status_next_fire")
        );
        mongoTemplate.indexOps(MongoScheduledTask.class).createIndex(
            new Index()
                .on("userId", Direction.ASC)
                .on("status", Direction.ASC)
                .named("idx_task_user_status")
        );
        System.out.println("[SCHEDULER-MONGO] 持久化就绪，集合：agent_scheduled_tasks");
    }

    @Override
    public void save(ScheduledTask task) {
        if (!props.isPersistenceEnabled()) return;
        try {
            MongoScheduledTask doc = MongoScheduledTask.fromRecord(task);
            mongoTemplate.save(doc);
            System.out.printf("[SCHEDULER-MONGO] 💾 保存 id=%s  userId=%s  status=%s  type=%s%n",
                shortId(task.id()), task.userId(), task.status(), task.type());
        } catch (Exception e) {
            System.err.println("[SCHEDULER-MONGO] ❌ 保存失败: " + e.getMessage());
            e.printStackTrace();
            throw e;
        }
    }

    @Override
    public void delete(String taskId) {
        try {
            mongoTemplate.remove(
                Query.query(Criteria.where("_id").is(taskId)),
                MongoScheduledTask.class
            );
            System.out.printf("[SCHEDULER-MONGO] 🗑 删除 id=%s%n", shortId(taskId));
        } catch (Exception e) {
            System.err.println("[SCHEDULER-MONGO] ❌ 删除失败: " + e.getMessage());
            e.printStackTrace();
        }
    }

    @Override
    public Optional<ScheduledTask> findById(String taskId) {
        MongoScheduledTask doc = mongoTemplate.findById(taskId, MongoScheduledTask.class);
        return doc == null ? Optional.empty() : Optional.of(doc.toRecord());
    }

    @Override
    public List<ScheduledTask> findByUserId(String userId) {
        Query query = Query.query(Criteria.where("userId").is(userId))
            .with(Sort.by(Direction.DESC, "createdAt"));
        return mongoTemplate.find(query, MongoScheduledTask.class)
            .stream()
            .map(MongoScheduledTask::toRecord)
            .collect(Collectors.toList());
    }

    @Override
    public List<ScheduledTask> findAllActive() {
        Query query = Query.query(Criteria.where("status").is("PENDING"))
            .with(Sort.by(Direction.ASC, "nextFireTime"));
        List<ScheduledTask> result = mongoTemplate.find(query, MongoScheduledTask.class)
            .stream()
            .map(MongoScheduledTask::toRecord)
            .collect(Collectors.toList());
        System.out.printf("[SCHEDULER-MONGO] 🔍 findAllActive 查询 status=PENDING 的任务，结果=%d 条%n", result.size());
        for (ScheduledTask t : result) {
            System.out.printf("   · id=%s  userId=%s  type=%s  next=%s%n",
                shortId(t.id()), t.userId(), t.type(),
                t.nextFireTime() != null ? t.nextFireTime().toString() : "null");
        }
        return result;
    }

    private static String shortId(String id) {
        return id == null || id.length() < 8 ? id : id.substring(0, 8);
    }
}