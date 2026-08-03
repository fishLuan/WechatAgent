package com.clawbot.wechatbot.feature.bilibili.rag.embedding;

import com.clawbot.wechatbot.config.BotConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/** DashScope OpenAI 兼容 Embedding 接口适配。 */
@Service
public class DashScopeEmbeddingService implements EmbeddingService {
    private final BotConfig config;
    private final HttpClient http;
    private final ObjectMapper mapper = new ObjectMapper();

    public DashScopeEmbeddingService(BotConfig config) {
        this.config = config;
        this.http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(config.getDashscopeConnectTimeoutSeconds()))
            .build();
    }

    @Override
    public boolean isConfigured() {
        return !config.getDashscopeApiKey().isBlank();
    }

    @Override
    public String model() {
        return config.getDashscopeEmbeddingModel();
    }

    @Override
    public int dimension() {
        return config.getDashscopeEmbeddingDimension();
    }

    @Override
    public List<List<Double>> embedDocuments(List<String> texts) throws Exception {
        return embed(texts, "document");
    }

    @Override
    public List<Double> embedQuery(String text) throws Exception {
        List<List<Double>> embeddings = embed(List.of(text == null ? "" : text), "query");
        return embeddings.isEmpty() ? List.of() : embeddings.getFirst();
    }

    private List<List<Double>> embed(List<String> texts, String textType) throws Exception {
        if (!isConfigured()) throw new IllegalStateException("DASHSCOPE_API_KEY 未配置");
        if (texts == null || texts.isEmpty()) return List.of();

        ObjectNode body = mapper.createObjectNode();
        body.put("model", model());
        if (dimension() > 0) {
            body.put("dimensions", dimension());
        }
        body.put("encoding_format", "float");
        body.putArray("input").addAll(texts.stream()
            .map(text -> mapper.getNodeFactory().textNode(text == null ? "" : text))
            .toList());
        body.put("text_type", textType);

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(config.getDashscopeEmbeddingEndpoint()))
            .timeout(Duration.ofSeconds(config.getDashscopeRequestTimeoutSeconds()))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer " + config.getDashscopeApiKey())
            .POST(HttpRequest.BodyPublishers.ofString(
                mapper.writeValueAsString(body), StandardCharsets.UTF_8))
            .build();
        HttpResponse<String> response = http.send(
            request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() != 200) {
            throw new Exception("DashScope Embedding 失败，HTTP "
                + response.statusCode() + "：" + preview(response.body()));
        }
        JsonNode data = mapper.readTree(response.body()).path("data");
        List<List<Double>> result = new ArrayList<>();
        if (data.isArray()) {
            for (JsonNode item : data) {
                List<Double> vector = new ArrayList<>();
                for (JsonNode value : item.path("embedding")) {
                    vector.add(value.asDouble());
                }
                result.add(List.copyOf(vector));
            }
        }
        return List.copyOf(result);
    }

    private String preview(String body) {
        if (body == null) return "";
        return body.length() > 300 ? body.substring(0, 300) + "..." : body;
    }
}
