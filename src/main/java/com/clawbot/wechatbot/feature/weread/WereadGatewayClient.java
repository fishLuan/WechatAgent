package com.clawbot.wechatbot.feature.weread;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 微信读书 Agent API 网关客户端。
 *
 * <p>统一入口 {@code POST https://i.weread.qq.com/api/agent/gateway}，
 * 鉴权 Bearer Key。注意官方规范：业务参数必须平铺在 JSON 顶层
 * （与 api_name / skill_version 同级），不能包在 params 对象里。</p>
 *
 * <p>实现说明：通过 {@code curl.exe} 发起请求（Windows 系统自带 System32，零新增依赖）。
 * 响应约定：成功时响应不含 errcode 字段，失败时才带非 0 errcode
 * （实测 JDK HttpClient 亦可正常访问，此前"TLS 指纹拒绝 JSSE"的推断有误，
 * 真正的问题是对成功响应的误判，见 {@link #parseResponse}）。</p>
 */
@Component
public class WereadGatewayClient {
    private static final String GATEWAY = "https://i.weread.qq.com/api/agent/gateway";
    private static final String SKILL_VERSION = "1.0.4";
    private static final long TIMEOUT_SECONDS = 20;

    private final WereadProperties properties;
    private final ObjectMapper objectMapper;

    public WereadGatewayClient(WereadProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
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
                if (value instanceof Integer i) {
                    body.put(entry.getKey(), i);
                } else if (value instanceof Long l) {
                    body.put(entry.getKey(), l);
                } else if (value instanceof Double d) {
                    body.put(entry.getKey(), d);
                } else if (value instanceof Number n) {
                    body.put(entry.getKey(), n.doubleValue());
                } else if (value instanceof Boolean b) {
                    body.put(entry.getKey(), b);
                } else {
                    body.put(entry.getKey(), String.valueOf(value));
                }
            }
        }
        String json = objectMapper.writeValueAsString(body);

        // body 写入临时文件（无 BOM）再交给 curl：Windows 命令行传 JSON 会剥引号导致格式错误
        Path tempBody = Files.createTempFile("weread", ".json");
        Path tempOutput = Files.createTempFile("weread-resp", ".json");
        try {
            Files.writeString(tempBody, json, StandardCharsets.UTF_8);
            List<String> command = new ArrayList<>(List.of(
                curlCommand(), "-s", "--noproxy", "*",
                "-X", "POST", GATEWAY,
                "-H", "Content-Type: application/json",
                "-H", "Authorization: Bearer " + properties.getApiKey(),
                "-d", "@" + tempBody));
            ProcessBuilder builder = new ProcessBuilder(command);
            // stdout 重定向到文件而非管道：Windows 匿名管道缓冲仅约 4KB，
            // 响应较大时 curl 写满管道阻塞，waitFor 无法感知退出而误判超时
            builder.redirectOutput(tempOutput.toFile());
            builder.redirectErrorStream(true);
            Process process = builder.start();
            boolean finished = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new IllegalStateException("微信读书网关请求超时（>"
                    + TIMEOUT_SECONDS + "s）");
            }
            String output = Files.readString(tempOutput, StandardCharsets.UTF_8);
            if (output.isBlank() || output.contains("\"errcode\":-1")
                || output.contains("\"errcode\": -1")) {
                System.err.println("[WEREAD] curl exit=" + process.exitValue()
                    + " 响应=" + truncate(output, 300));
            }
            return parseResponse(output);
        } finally {
            Files.deleteIfExists(tempBody);
            Files.deleteIfExists(tempOutput);
        }
    }

    /** Windows 用 curl.exe（System32 自带），Linux/macOS 用 curl。 */
    private static String curlCommand() {
        return System.getProperty("os.name", "").toLowerCase().contains("win")
            ? "curl.exe" : "curl";
    }

    private static String truncate(String value, int max) {
        if (value == null) return "";
        return value.length() <= max ? value : value.substring(0, max) + "…";
    }

    private JsonNode parseResponse(String output) throws Exception {
        if (output == null || output.isBlank()) {
            throw new IllegalStateException("微信读书网关无响应");
        }
        JsonNode root = objectMapper.readTree(output);
        // 网关约定：成功响应不含 errcode 字段，缺失视为成功；失败响应才带非 0 errcode
        int errcode = root.path("errcode").asInt(0);
        if (errcode != 0) {
            throw new IllegalStateException("微信读书接口失败："
                + root.path("errmsg").asText("errcode=" + errcode));
        }
        return root;
    }
}
