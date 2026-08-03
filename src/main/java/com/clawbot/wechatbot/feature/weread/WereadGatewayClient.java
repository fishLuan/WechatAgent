package com.clawbot.wechatbot.feature.weread;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

/**
 * 微信读书 Agent API 网关客户端。
 *
 * <p>统一入口 {@code POST https://i.weread.qq.com/api/agent/gateway}，
 * 鉴权 Bearer Key。注意官方规范：业务参数必须平铺在 JSON 顶层
 * （与 api_name / skill_version 同级），不能包在 params 对象里。</p>
 */
@Component
public class WereadGatewayClient {
    private static final String GATEWAY = "https://i.weread.qq.com/api/agent/gateway";
    private static final String SKILL_VERSION = "1.0.4";
    private static final Duration TIMEOUT = Duration.ofSeconds(20);

    private final WereadProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public WereadGatewayClient(WereadProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        // 微信读书为腾讯国内服务，直连无需代理
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    }

    /**
     * 调用网关接口。
     *
     * @param apiName 接口名（如 /shelf/sync）
     * @param params  业务参数（平铺到 JSON 顶层）
     * @return 响应 JSON（errcode=0 时业务数据在顶层；失败时含 errcode/errmsg）
     */
    public JsonNode call(String apiName, Map<String, Object> params) throws Exception {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("api_name", apiName);
        body.put("skill_version", SKILL_VERSION);
        if (params != null) {
            for (Map.Entry<String, Object> entry : params.entrySet()) {
                Object value = entry.getValue();
                if (value == null) continue;
                if (value instanceof Number number) {
                    body.put(entry.getKey(), number);
                } else if (value instanceof Boolean bool) {
                    body.put(entry.getKey(), bool);
                } else {
                    body.put(entry.getKey(), String.valueOf(value));
                }
            }
        }

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(GATEWAY))
            .timeout(TIMEOUT)
            .header("Content-Type", "application/json; charset=utf-8")
            .header("Authorization", "Bearer " + properties.getApiKey())
            .POST(HttpRequest.BodyPublishers.ofString(
                objectMapper.writeValueAsString(body), StandardCharsets.UTF_8))
            .build();
        HttpResponse<String> response = httpClient.send(
            request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() != 200) {
            throw new IllegalStateException(
                "微信读书网关请求失败，HTTP " + response.statusCode());
        }
        JsonNode root = objectMapper.readTree(response.body());
        int errcode = root.path("errcode").asInt(-1);
        if (errcode != 0) {
            throw new IllegalStateException("微信读书接口失败："
                + root.path("errmsg").asText("errcode=" + errcode));
        }
        return root;
    }
}
