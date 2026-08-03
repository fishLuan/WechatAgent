package com.clawbot.wechatbot.feature.bilibili.agent;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BilibiliTaskPlannerTests {
    private final BilibiliTaskPlanner planner = new BilibiliTaskPlanner();

    @Test
    void plansSearchThenSubscriptionWithDependency() {
        List<BilibiliTask> tasks = planner.plan(
            "搜索牧神记，然后订阅第一个", 5);

        assertEquals(2, tasks.size());
        assertEquals(BilibiliTaskType.SEARCH_CONTENT, tasks.get(0).type());
        assertEquals(BilibiliTaskType.SUBSCRIBE_CONTENT, tasks.get(1).type());
        assertEquals(List.of("bili-task-1"), tasks.get(1).dependencies());
    }

    @Test
    void plansRecommendationAndPushConfiguration() {
        List<BilibiliTask> tasks = planner.plan(
            "推荐高分动漫，并且每天十点推送电影", 5);

        assertEquals(2, tasks.size());
        assertEquals(BilibiliTaskType.RECOMMEND_CONTENT, tasks.get(0).type());
        assertEquals(BilibiliTaskType.CONFIGURE_PUSH, tasks.get(1).type());
    }

    @Test
    void rejectsPlanOverTaskLimit() {
        assertThrows(IllegalArgumentException.class, () -> planner.plan(
            "搜索甲，然后搜索乙，然后搜索丙", 2));
    }

    @Test
    void rejectsCompoundPlanContainingUnsupportedTaskInsteadOfDroppingIt() {
        assertThrows(IllegalArgumentException.class, () -> planner.plan(
            "搜索牧神记，然后删除全部历史", 5));
    }
}
