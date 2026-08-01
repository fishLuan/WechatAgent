package com.clawbot.wechatbot.feature.bilibili.recommendation;

import com.clawbot.wechatbot.feature.bilibili.messaging.BilibiliMessageFormatter;
import com.clawbot.wechatbot.feature.bilibili.model.ContentType;
import com.clawbot.wechatbot.scheduler.model.TaskType;
import com.clawbot.wechatbot.scheduler.task.ScheduledTaskContentProvider;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

@Component
public class BilibiliRecommendationContentProvider
    implements ScheduledTaskContentProvider {

    private final BilibiliRecommendationService recommendations;
    private final ObjectMapper mapper;

    public BilibiliRecommendationContentProvider(
        BilibiliRecommendationService recommendations,
        ObjectMapper mapper
    ) {
        this.recommendations = recommendations;
        this.mapper = mapper;
    }

    @Override
    public TaskType taskType() {
        return TaskType.BILIBILI_RECOMMENDATION;
    }

    @Override
    public String provideContent(String userId, String paramsJson) throws Exception {
        JsonNode params = mapper.readTree(paramsJson);
        ContentType type = ContentType.valueOf(
            params.path("bilibili_content_type").asText());
        int count = Math.max(1, params.path("recommendation_count").asInt(3));
        return BilibiliMessageFormatter.formatRecommendation(
            recommendations.recommend(userId, type, count));
    }
}
