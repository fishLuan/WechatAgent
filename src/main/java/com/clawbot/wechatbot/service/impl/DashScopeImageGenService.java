package com.clawbot.wechatbot.service.impl;

import com.clawbot.wechatbot.service.ImageGenService;
import com.clawbot.wechatbot.service.client.DashScopeClient;
import com.clawbot.wechatbot.service.support.DashScopeResponseParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** DashScope 文生图服务，只处理图片生成请求。 */
public class DashScopeImageGenService implements ImageGenService {
    private final DashScopeClient client;
    private final String model;
    private final String defaultSize;
    private final int defaultCount;
    private final boolean promptExtend;
    private final boolean watermark;

    public DashScopeImageGenService(DashScopeClient client, String model, String defaultSize,
                                    int defaultCount, boolean promptExtend, boolean watermark) {
        this.client = client;
        this.model = model;
        this.defaultSize = defaultSize;
        this.defaultCount = defaultCount;
        this.promptExtend = promptExtend;
        this.watermark = watermark;
    }

    @Override
    public byte[] generateImage(String prompt) throws Exception {
        if (prompt == null || prompt.isBlank()) throw new IllegalArgumentException("图片描述不能为空");

        ObjectNode body = client.mapper().createObjectNode();
        body.put("model", model);
        ArrayNode messages = body.putObject("input").putArray("messages");
        messages.addObject().put("role", "user").putArray("content")
            .addObject().put("text", prompt.trim());
        ObjectNode parameters = body.putObject("parameters");
        parameters.put("size", defaultSize);
        parameters.put("n", defaultCount);
        parameters.put("prompt_extend", promptExtend);
        parameters.put("watermark", watermark);

        JsonNode response = client.post(body, "文生图");
        String imageUrl = DashScopeResponseParser.extractImageUrl(response);
        if (imageUrl.isBlank()) throw new Exception("无法从文生图响应中解析图片地址");
        return client.download(imageUrl, "生成图片");
    }

    @Override
    public boolean isConfigured() {
        return client.isConfigured();
    }
}
