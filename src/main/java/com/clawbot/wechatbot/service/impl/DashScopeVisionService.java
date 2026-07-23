package com.clawbot.wechatbot.service.impl;

import com.clawbot.wechatbot.service.VisionService;
import com.clawbot.wechatbot.service.client.DashScopeClient;
import com.clawbot.wechatbot.service.support.DashScopeResponseParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Base64;

/** DashScope 图片理解服务，只处理视觉请求的输入和输出。 */
public class DashScopeVisionService implements VisionService {
    private final DashScopeClient client;
    private final String model;
    private final String defaultQuestion;

    public DashScopeVisionService(DashScopeClient client, String model, String defaultQuestion) {
        this.client = client;
        this.model = model;
        this.defaultQuestion = defaultQuestion;
    }

    @Override
    public String understandImage(byte[] imageBytes, String question) throws Exception {
        if (imageBytes == null || imageBytes.length == 0) {
            throw new IllegalArgumentException("图片数据不能为空");
        }
        String actualQuestion = question == null || question.isBlank() ? defaultQuestion : question.trim();
        String dataUrl = "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(imageBytes);

        ObjectNode body = client.mapper().createObjectNode();
        body.put("model", model);
        ArrayNode messages = body.putObject("input").putArray("messages");
        ArrayNode content = messages.addObject().put("role", "user").putArray("content");
        content.addObject().put("image", dataUrl);
        content.addObject().put("text", actualQuestion);

        JsonNode response = client.post(body, "图片理解");
        String result = DashScopeResponseParser.extractMessageText(response);
        if (result.isBlank()) throw new Exception("无法解析图片理解响应");
        return result.trim();
    }

    @Override
    public boolean isConfigured() {
        return client.isConfigured();
    }
}
