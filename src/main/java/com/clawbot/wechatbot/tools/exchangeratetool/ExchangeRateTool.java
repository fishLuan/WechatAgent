package com.clawbot.wechatbot.tools.exchangeratetool;

import com.clawbot.wechatbot.tools.FunctionTool;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;

/** 聚合数据全球汇率查询换算工具。 */
public class ExchangeRateTool implements FunctionTool {
    private static final String DEFAULT_ENDPOINT = "https://op.juhe.cn/onebox/exchange/currency";
    private static final Map<String, String> CURRENCY_ALIASES = Map.ofEntries(
        Map.entry("人民币", "CNY"), Map.entry("人民币元", "CNY"), Map.entry("元", "CNY"),
        Map.entry("美元", "USD"), Map.entry("美金", "USD"),
        Map.entry("欧元", "EUR"), Map.entry("日元", "JPY"), Map.entry("日币", "JPY"),
        Map.entry("英镑", "GBP"), Map.entry("港币", "HKD"), Map.entry("港元", "HKD"),
        Map.entry("澳门元", "MOP"), Map.entry("澳元", "AUD"), Map.entry("澳大利亚元", "AUD"),
        Map.entry("加元", "CAD"), Map.entry("加拿大元", "CAD"),
        Map.entry("韩元", "KRW"), Map.entry("韩币", "KRW"),
        Map.entry("新加坡元", "SGD"), Map.entry("新币", "SGD"),
        Map.entry("瑞士法郎", "CHF"), Map.entry("新西兰元", "NZD"),
        Map.entry("卢布", "RUB"), Map.entry("俄罗斯卢布", "RUB"),
        Map.entry("泰铢", "THB"), Map.entry("马来西亚林吉特", "MYR"),
        Map.entry("印度卢比", "INR")
    );

    private final String apiKey;
    private final String endpoint;
    private final String version;
    private final HttpClient http;
    private final ObjectMapper mapper;
    private final Duration requestTimeout;

    public ExchangeRateTool(String apiKey) {
        this(apiKey, DEFAULT_ENDPOINT, "2", 10, 15);
    }

    public ExchangeRateTool(String apiKey, String endpoint, String version,
                            int connectTimeoutSeconds, int requestTimeoutSeconds) {
        this(apiKey, endpoint, version,
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(connectTimeoutSeconds)).build(),
            new ObjectMapper(), Duration.ofSeconds(requestTimeoutSeconds));
    }

    ExchangeRateTool(String apiKey, String endpoint, String version, HttpClient http,
                     ObjectMapper mapper, Duration requestTimeout) {
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.endpoint = endpoint == null || endpoint.isBlank() ? DEFAULT_ENDPOINT : endpoint.trim();
        this.version = version == null || version.isBlank() ? "2" : version.trim();
        this.http = http;
        this.mapper = mapper;
        this.requestTimeout = requestTimeout;
    }

    @Override
    public String name() {
        return "convert_currency";
    }

    @Override
    public JsonNode definition() {
        ObjectNode function = mapper.createObjectNode();
        function.put("name", name());
        function.put("description", "查询实时汇率并换算金额。涉及当前汇率、外币兑换或两种货币换算时调用；货币可传三位代码或常见中文名称。");

        ObjectNode parameters = function.putObject("parameters");
        parameters.put("type", "object");
        ObjectNode properties = parameters.putObject("properties");
        properties.putObject("amount")
            .put("type", "number")
            .put("minimum", 0)
            .put("description", "需要换算的金额，例如 100；仅查询汇率时可省略，默认按 1 单位换算");
        properties.putObject("from_currency")
            .put("type", "string")
            .put("description", "源货币，优先使用 ISO 4217 三位代码，例如 USD；也支持美元、人民币等常见中文名称");
        properties.putObject("to_currency")
            .put("type", "string")
            .put("description", "目标货币，优先使用 ISO 4217 三位代码，例如 CNY；也支持美元、人民币等常见中文名称");
        properties.putObject("precision")
            .put("type", "integer")
            .put("minimum", 0)
            .put("maximum", 8)
            .put("description", "换算结果保留的小数位数，默认 2");
        parameters.putArray("required").add("from_currency").add("to_currency");

        ObjectNode tool = mapper.createObjectNode();
        tool.put("type", "function");
        tool.set("function", function);
        return tool;
    }

    @Override
    public String execute(JsonNode arguments) throws Exception {
        if (apiKey.isEmpty()) {
            return error("聚合数据汇率 API Key 未配置，请设置 JUHE_EXCHANGE_API_KEY");
        }
        if (arguments == null || !arguments.isObject()) return error("工具参数必须是 JSON 对象");

        BigDecimal amount = BigDecimal.ONE;
        try {
            JsonNode amountNode = arguments.get("amount");
            if (amountNode != null && !amountNode.isNull()
                && !amountNode.isNumber() && !amountNode.isTextual()) {
                return error("amount 参数必须是数字");
            }
            if (amountNode != null && !amountNode.isNull()) {
                amount = new BigDecimal(amountNode.asText().trim());
            }
        } catch (NumberFormatException e) {
            return error("amount 参数必须是有效数字");
        }
        if (amount.signum() < 0) return error("amount 参数不能小于 0");

        String from = normalizeCurrency(arguments.path("from_currency").asText(""));
        String to = normalizeCurrency(arguments.path("to_currency").asText(""));
        if (from.isEmpty()) return error("from_currency 参数不能为空或不是有效的三位货币代码");
        if (to.isEmpty()) return error("to_currency 参数不能为空或不是有效的三位货币代码");

        int precision = arguments.path("precision").asInt(2);
        if (precision < 0 || precision > 8) return error("precision 必须在 0 到 8 之间");
        if (from.equals(to)) return sameCurrencyResult(amount, from, precision);

        String url = endpoint + "?key=" + encode(apiKey)
            + "&from=" + encode(from) + "&to=" + encode(to) + "&version=" + encode(version);
        HttpRequest request = HttpRequest.newBuilder().uri(URI.create(url))
            .timeout(requestTimeout).GET().build();
        HttpResponse<String> response = http.send(request,
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() != 200) {
            return error("聚合数据汇率接口返回 HTTP " + response.statusCode());
        }

        JsonNode root = mapper.readTree(response.body());
        int errorCode = root.path("error_code").asInt(-1);
        if (errorCode != 0) {
            return error("聚合数据汇率查询失败（" + errorCode + "）："
                + root.path("reason").asText("未知错误"));
        }

        JsonNode rateItem = findDirection(root.path("result"), from, to);
        if (rateItem == null) return error("接口未返回 " + from + " 到 " + to + " 的汇率");

        BigDecimal exchangeRate;
        try {
            exchangeRate = new BigDecimal(rateItem.path("exchange").asText());
        } catch (NumberFormatException e) {
            return error("接口返回的汇率格式无效");
        }
        BigDecimal convertedAmount = amount.multiply(exchangeRate).setScale(precision, RoundingMode.HALF_UP);

        ObjectNode result = mapper.createObjectNode();
        result.put("success", true);
        result.put("source_currency", from);
        result.put("source_currency_name", rateItem.path("currencyF_Name").asText(from));
        result.put("source_amount", amount.toPlainString());
        result.put("target_currency", to);
        result.put("target_currency_name", rateItem.path("currencyT_Name").asText(to));
        result.put("target_amount", convertedAmount.toPlainString());
        result.put("exchange_rate", exchangeRate.toPlainString());
        result.put("update_time", rateItem.path("updateTime").asText(""));
        result.put("notice", "汇率数据仅供参考，以实际交易价格为准");
        return mapper.writeValueAsString(result);
    }

    private JsonNode findDirection(JsonNode items, String from, String to) {
        if (!items.isArray()) return null;
        for (JsonNode item : items) {
            if (from.equalsIgnoreCase(item.path("currencyF").asText())
                && to.equalsIgnoreCase(item.path("currencyT").asText())) {
                return item;
            }
        }
        return null;
    }

    private String sameCurrencyResult(BigDecimal amount, String currency, int precision) throws Exception {
        ObjectNode result = mapper.createObjectNode();
        result.put("success", true);
        result.put("source_currency", currency);
        result.put("source_amount", amount.toPlainString());
        result.put("target_currency", currency);
        result.put("target_amount", amount.setScale(precision, RoundingMode.HALF_UP).toPlainString());
        result.put("exchange_rate", "1");
        result.put("notice", "源货币和目标货币相同，无需查询实时汇率");
        return mapper.writeValueAsString(result);
    }

    private String error(String message) throws Exception {
        ObjectNode result = mapper.createObjectNode();
        result.put("success", false);
        result.put("error", message);
        return mapper.writeValueAsString(result);
    }

    static String normalizeCurrency(String value) {
        if (value == null) return "";
        String normalized = value.trim();
        String alias = CURRENCY_ALIASES.get(normalized);
        if (alias != null) return alias;
        normalized = normalized.toUpperCase(Locale.ROOT);
        return normalized.matches("[A-Z]{3}") ? normalized : "";
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
