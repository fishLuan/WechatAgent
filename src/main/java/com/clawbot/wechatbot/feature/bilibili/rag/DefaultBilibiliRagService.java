package com.clawbot.wechatbot.feature.bilibili.rag;

import com.clawbot.wechatbot.feature.bilibili.model.ContentType;
import com.clawbot.wechatbot.feature.bilibili.rag.generation.BilibiliRagAnswerGenerator;
import com.clawbot.wechatbot.feature.bilibili.rag.model.BilibiliRagRequest;
import com.clawbot.wechatbot.feature.bilibili.rag.retrieval.BilibiliRagContextBuilder;
import org.springframework.stereotype.Service;

@Service
public class DefaultBilibiliRagService implements BilibiliRagService {
    private final BilibiliRagContextBuilder contextBuilder;
    private final BilibiliRagAnswerGenerator answerGenerator;

    public DefaultBilibiliRagService(
        BilibiliRagContextBuilder contextBuilder,
        BilibiliRagAnswerGenerator answerGenerator
    ) {
        this.contextBuilder = contextBuilder;
        this.answerGenerator = answerGenerator;
    }

    @Override
    public String answer(String wechatUserId, String question, ContentType preferredType) {
        BilibiliRagRequest request =
            new BilibiliRagRequest(wechatUserId, question, preferredType, null);
        return answerGenerator.generate(contextBuilder.build(request));
    }

    @Override
    public String answerSimilar(
        String wechatUserId, String title, ContentType preferredType
    ) {
        String question = "推荐类似《" + title + "》的 B 站作品";
        ContentType type = preferredType == null ? ContentType.BANGUMI : preferredType;
        BilibiliRagRequest request =
            new BilibiliRagRequest(wechatUserId, question, type, title);
        return answerGenerator.generate(contextBuilder.build(request));
    }
}
