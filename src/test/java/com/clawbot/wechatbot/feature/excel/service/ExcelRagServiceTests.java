package com.clawbot.wechatbot.feature.excel.service;

import com.clawbot.wechatbot.feature.excel.model.ExcelRagKnowledge;
import com.clawbot.wechatbot.feature.excel.repository.ExcelRagKnowledgeRepository;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Example;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.repository.query.FluentQuery;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import static com.clawbot.wechatbot.feature.excel.model.ExcelRagKnowledge.CATEGORY_BUSINESS_RULE;
import static com.clawbot.wechatbot.feature.excel.model.ExcelRagKnowledge.CATEGORY_FIELD_MAPPING;
import static com.clawbot.wechatbot.feature.excel.model.ExcelRagKnowledge.CATEGORY_OPERATION_EXAMPLE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 知识库服务测试：启动种子、增删查、列别名解析与规则命中。 */
class ExcelRagServiceTests {

    private ExcelRagService seededService(FakeRagRepository fake) {
        ExcelRagService service = new ExcelRagService(fake);
        service.seedIfEmpty();
        return service;
    }

    // ============================
    // 启动种子
    // ============================
    @Test
    void seedsBaselineKnowledgeWhenCollectionEmpty() {
        FakeRagRepository fake = new FakeRagRepository();
        ExcelRagService service = seededService(fake);

        assertEquals(5, fake.count());
        // 种子字段映射可解析别名
        assertEquals("营业收入", service.resolveColumnAlias("营收"));
        assertEquals("营业收入", service.resolveColumnAlias("销售收入"));
        assertEquals("销售额", service.resolveColumnAlias("销售金额"));
    }

    @Test
    void doesNotSeedWhenKnowledgeExists() {
        FakeRagRepository fake = new FakeRagRepository();
        fake.save(new ExcelRagKnowledge(CATEGORY_FIELD_MAPPING,
            List.of("已有"), "已有列", null, null));
        ExcelRagService service = seededService(fake);

        assertEquals(1, fake.count());
    }

    // ============================
    // 添加 / 列表
    // ============================
    @Test
    void addPersistsKnowledgeEntry() {
        FakeRagRepository fake = new FakeRagRepository();
        ExcelRagService service = new ExcelRagService(fake);

        ExcelRagKnowledge saved = service.add(CATEGORY_BUSINESS_RULE,
            List.of("毛利"), null, "毛利润 = 营业收入 - 营业成本", null);

        assertNotNull(saved.getId());
        assertEquals(1, fake.count());
        assertEquals(CATEGORY_BUSINESS_RULE, saved.getCategory());
    }

    @Test
    void listReturnsNewestFirstWithLimit() {
        FakeRagRepository fake = new FakeRagRepository();
        ExcelRagService service = new ExcelRagService(fake);
        service.add(CATEGORY_BUSINESS_RULE, List.of("第一条"), null, "规则一", null);
        service.add(CATEGORY_BUSINESS_RULE, List.of("第二条"), null, "规则二", null);
        service.add(CATEGORY_BUSINESS_RULE, List.of("第三条"), null, "规则三", null);

        List<ExcelRagKnowledge> recent = service.list(2);

        assertEquals(2, recent.size());
        assertEquals("规则三", recent.get(0).getRule());
        assertEquals("规则二", recent.get(1).getRule());
    }

    @Test
    void countReportsTotalEntries() {
        FakeRagRepository fake = new FakeRagRepository();
        ExcelRagService service = seededService(fake);
        service.add(CATEGORY_OPERATION_EXAMPLE, List.of("新示例"), null, null, "示例内容");

        assertEquals(6, service.count());
    }

    // ============================
    // 删除
    // ============================
    @Test
    void deleteByKeywordRemovesMatchedEntries() {
        FakeRagRepository fake = new FakeRagRepository();
        ExcelRagService service = seededService(fake);

        assertTrue(service.deleteByKeyword("营收"));

        assertEquals(4, fake.count());
        assertNull(service.resolveColumnAlias("营收"));
    }

    @Test
    void deleteByKeywordReturnsFalseWhenNoMatch() {
        FakeRagRepository fake = new FakeRagRepository();
        ExcelRagService service = seededService(fake);

        assertFalse(service.deleteByKeyword("不存在的词"));
        assertEquals(5, fake.count());
    }

    // ============================
    // 列别名解析
    // ============================
    @Test
    void resolveColumnAliasHitsEqualOrContainingKeyword() {
        ExcelRagService service = seededService(new FakeRagRepository());

        // 触发词与 term 相等
        assertEquals("营业收入", service.resolveColumnAlias("营收"));
        // 触发词互相包含（「收入」包含于「营业收入」；「金额」包含于「销售金额」）
        assertEquals("营业收入", service.resolveColumnAlias("收入"));
        assertEquals("销售额", service.resolveColumnAlias("金额"));
        assertEquals("销售额", service.resolveColumnAlias("销售额"));
    }

    @Test
    void resolveColumnAliasReturnsNullWhenNoHit() {
        ExcelRagService service = seededService(new FakeRagRepository());

        // 知识库未收录的别名（如「营业额」）返回 null，交给既有模糊匹配兜底
        assertNull(service.resolveColumnAlias("营业额"));
        assertNull(service.resolveColumnAlias(""));
        assertNull(service.resolveColumnAlias(null));
    }

    // ============================
    // 规则命中
    // ============================
    @Test
    void findRulesReturnsMatchingNonFieldKnowledge() {
        ExcelRagService service = seededService(new FakeRagRepository());

        List<ExcelRagKnowledge> hits = service.findRules("请检查库存预警");

        assertEquals(1, hits.size());
        assertEquals(CATEGORY_BUSINESS_RULE, hits.get(0).getCategory());
        assertTrue(hits.get(0).getRule().contains("库存预警"));
    }

    @Test
    void findRulesExcludesFieldMappingKnowledge() {
        ExcelRagService service = seededService(new FakeRagRepository());

        // 字段映射不算规则：即使文本命中「营收」也不返回
        assertTrue(service.findRules("营收").isEmpty());
    }

    @Test
    void findRulesWithNoMatchReturnsEmpty() {
        ExcelRagService service = seededService(new FakeRagRepository());

        assertTrue(service.findRules("你好").isEmpty());
        assertTrue(service.findRules("").isEmpty());
        assertTrue(service.findRules(null).isEmpty());
    }

    /** 内存版知识库仓库：模拟 Mongo 的数组包含匹配、按创建时间倒序、保存/删除。 */
    private static final class FakeRagRepository implements ExcelRagKnowledgeRepository {
        private final Map<String, ExcelRagKnowledge> store = new LinkedHashMap<>();
        private long seq;
        private long timeSeq;

        @Override
        public <S extends ExcelRagKnowledge> S save(S knowledge) {
            if (knowledge.getId() == null) {
                knowledge.setId("k" + (++seq));
            }
            // 模拟 MongoDB 单调递增的写入时间，保证「最近若干条」倒序确定
            knowledge.setCreatedAt(Instant.ofEpochMilli(++timeSeq));
            store.put(knowledge.getId(), knowledge);
            return knowledge;
        }

        @Override
        public <S extends ExcelRagKnowledge> S insert(S entity) {
            return save(entity);
        }

        @Override
        public <S extends ExcelRagKnowledge> List<S> insert(Iterable<S> entities) {
            return saveAll(entities);
        }

        @Override
        public <S extends ExcelRagKnowledge> List<S> saveAll(Iterable<S> entities) {
            List<S> saved = new ArrayList<>();
            for (S entity : entities) {
                saved.add(save(entity));
            }
            return saved;
        }

        @Override
        public Optional<ExcelRagKnowledge> findById(String id) {
            return Optional.ofNullable(store.get(id));
        }

        @Override
        public boolean existsById(String id) {
            return store.containsKey(id);
        }

        @Override
        public List<ExcelRagKnowledge> findAll() {
            return new ArrayList<>(store.values());
        }

        @Override
        public List<ExcelRagKnowledge> findAllById(Iterable<String> ids) {
            List<ExcelRagKnowledge> result = new ArrayList<>();
            for (String id : ids) {
                ExcelRagKnowledge knowledge = store.get(id);
                if (knowledge != null) {
                    result.add(knowledge);
                }
            }
            return result;
        }

        @Override
        public long count() {
            return store.size();
        }

        @Override
        public void deleteById(String id) {
            store.remove(id);
        }

        @Override
        public void delete(ExcelRagKnowledge knowledge) {
            store.remove(knowledge.getId());
        }

        @Override
        public void deleteAllById(Iterable<? extends String> ids) {
            for (String id : ids) {
                store.remove(id);
            }
        }

        @Override
        public void deleteAll(Iterable<? extends ExcelRagKnowledge> entities) {
            for (ExcelRagKnowledge knowledge : entities) {
                store.remove(knowledge.getId());
            }
        }

        @Override
        public void deleteAll() {
            store.clear();
        }

        @Override
        public List<ExcelRagKnowledge> findAll(Sort sort) {
            return new ArrayList<>(store.values());
        }

        @Override
        public Page<ExcelRagKnowledge> findAll(Pageable pageable) {
            return new PageImpl<>(new ArrayList<>(store.values()));
        }

        // QueryByExampleExecutor 的方法在本测试中不使用，给出空实现即可
        @Override
        public <S extends ExcelRagKnowledge> Optional<S> findOne(Example<S> example) {
            return Optional.empty();
        }

        @Override
        public <S extends ExcelRagKnowledge> List<S> findAll(Example<S> example) {
            return List.of();
        }

        @Override
        public <S extends ExcelRagKnowledge> List<S> findAll(Example<S> example, Sort sort) {
            return List.of();
        }

        @Override
        public <S extends ExcelRagKnowledge> Page<S> findAll(Example<S> example,
                                                             Pageable pageable) {
            return Page.empty();
        }

        @Override
        public <S extends ExcelRagKnowledge> long count(Example<S> example) {
            return 0;
        }

        @Override
        public <S extends ExcelRagKnowledge> boolean exists(Example<S> example) {
            return false;
        }

        @Override
        public <S extends ExcelRagKnowledge, R> R findBy(Example<S> example,
            Function<FluentQuery.FetchableFluentQuery<S>, R> queryFunction) {
            return null;
        }

        @Override
        public List<ExcelRagKnowledge> findByKeywordsContaining(String keyword) {
            return store.values().stream()
                .filter(knowledge -> knowledge.getKeywords().stream()
                    .anyMatch(value -> value.contains(keyword)))
                .toList();
        }

        @Override
        public List<ExcelRagKnowledge> findAllByOrderByCreatedAtDesc() {
            return store.values().stream()
                .sorted(Comparator.comparing(ExcelRagKnowledge::getCreatedAt).reversed())
                .toList();
        }
    }
}
