package com.clawbot.wechatbot.service.impl;

import com.clawbot.wechatbot.service.SpeechSynthesisService;
import com.clawbot.wechatbot.service.client.DashScopeClient;
import com.clawbot.wechatbot.service.support.DashScopeResponseParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** DashScope 语音合成服务，只处理文字转音频。 */
public class DashScopeSpeechSynthesisService implements SpeechSynthesisService {
    private final DashScopeClient client;
    private final String model;
    private final String defaultVoice;
    private final String format;
    private final int maxTextLength;

    public DashScopeSpeechSynthesisService(DashScopeClient client, String model, String defaultVoice,
                                           String format, int maxTextLength) {
        this.client = client;
        this.model = model;
        this.defaultVoice = defaultVoice;
        this.format = format;
        this.maxTextLength = maxTextLength;
    }

    @Override
    public byte[] synthesize(String text) throws Exception {
        return synthesize(text, defaultVoice);
    }

    @Override
    public byte[] synthesize(String text, String voice) throws Exception {
        if (text == null || text.isBlank()) throw new IllegalArgumentException("合成文字不能为空");
        String actualText = text.length() > maxTextLength ? text.substring(0, maxTextLength) : text;
        String actualVoice = voice == null || voice.isBlank() ? defaultVoice : voice.trim();

        ObjectNode body = client.mapper().createObjectNode();
        body.put("model", model);
        ObjectNode input = body.putObject("input");
        input.put("text", actualText);
        input.put("voice", actualVoice);
        input.put("format", format);

        JsonNode response = client.post(body, "语音合成");
        String audioUrl = DashScopeResponseParser.extractAudioUrl(response);
        if (audioUrl.isBlank()) throw new Exception("无法从语音合成响应中解析音频地址");
        return client.download(audioUrl, "合成音频");
    }

    @Override
    public String getFileExtension() {
        return format;
    }
}
