package com.clawbot.wechatbot.tools.pathplantool;

import com.clawbot.wechatbot.tools.FunctionTool;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 高德地图路线规划工具。支持 4 种出行方式：驾车 / 公交地铁 / 步行 / 骑行。
 * <p>
 * 用法（大模型 function-calling 或手动调用都可以）：
 *   origin        = 起点地址（中文地址或 经度,纬度 格式，例如：北京西站 或 116.327061,39.893715）
 *   destination   = 终点地址（中文地址或 经度,纬度 格式，例如：天安门广场 或 116.397428,39.90923）
 *   strategy      = 出行方式：driving（驾车，默认）/ transit（公交地铁）/ walking（步行）/ riding（骑行）
 *   city          = 公交策略时必填：所在城市中文名称，例如：北京、上海、杭州
 * <p>
 * 需要配置的环境变量（无需改其他文件，启动时注入即可）：AMAP_ROUTE_API_KEY 或 AMAP_WEATHER_API_KEY
 *   - 如果你的项目已经有 AmapWeatherTool，直接复用同一个高德 Key 就行。
 */
public class PathPlanTool implements FunctionTool {

    private static final Pattern LNG_LAT = Pattern.compile("^\\s*(-?\\d+\\.?\\d*)\\s*,\\s*(-?\\d+\\.?\\d*)\\s*$");

    private static final String GEOCODE_ENDPOINT  = "https://restapi.amap.com/v3/geocode/geo";
    private static final String DRIVING_ENDPOINT  = "https://restapi.amap.com/v3/direction/driving";
    private static final String TRANSIT_ENDPOINT  = "https://restapi.amap.com/v3/direction/transit/integrated";
    private static final String WALKING_ENDPOINT  = "https://restapi.amap.com/v3/direction/walking";
    private static final String RIDING_ENDPOINT   = "https://restapi.amap.com/v4/direction/bicycling";

    private final String apiKey;
    private final HttpClient http;
    private final ObjectMapper mapper;
    private final Duration requestTimeout;

    public PathPlanTool() {
        this(resolveApiKeyFromEnv());
    }

    public PathPlanTool(String apiKey) {
        this(apiKey, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(),
             new ObjectMapper(), Duration.ofSeconds(15));
    }

    public PathPlanTool(String apiKey, String geocodeEndpointIgnored, int connectTimeoutSeconds, int requestTimeoutSeconds) {
        this(apiKey, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(connectTimeoutSeconds)).build(),
             new ObjectMapper(), Duration.ofSeconds(requestTimeoutSeconds));
    }

    PathPlanTool(String apiKey, HttpClient http, ObjectMapper mapper, Duration requestTimeout) {
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.http = http;
        this.mapper = mapper;
        this.requestTimeout = requestTimeout;
    }

    @Override
    public String name() {
        return "get_route_plan";
    }

    @Override
    public JsonNode definition() {
        ObjectNode function = mapper.createObjectNode();
        function.put("name", name());
        function.put("description", "【Top 2 优先级工具，必须优先调用，绝对禁止用 web_search 代替】在中国境内，根据起点和终点规划出行路线、交通方式、路程距离、耗时、打车费等。支持 4 种方式：driving 自驾车/开车、transit 公交地铁/公共交通、walking 步行、riding 骑行/电动车/自行车。返回总距离、总耗时、分段指引步骤。触发关键词（命中任意一个必须调我，禁止调 web_search）：路线、怎么走、怎么去、自驾、开车、公交、地铁、步行、骑行、打车、距离、耗时、从哪里到哪里、从A到B、去机场/火车站怎么走。【强规则 1 强制执行】只要命中了路线/出行/从哪到哪类关键词，必须先调我，不管我返回成功或失败，都绝对禁止再调 web_search！禁止 fallback 第二个工具！失败了就直接把错误信息告诉用户，不许再试别的工具搜路线！");
        ObjectNode parameters = function.putObject("parameters");
        parameters.put("type", "object");
        ObjectNode props = parameters.putObject("properties");

        props.putObject("origin")
             .put("type", "string")
             .put("description", "起点地址：中文地址（例如 北京西站、杭州市西湖区支付宝大厦）或 经纬度 经度,纬度 格式");
        props.putObject("destination")
             .put("type", "string")
             .put("description", "终点地址：中文地址（例如 天安门广场、上海虹桥火车站）或 经纬度 经度,纬度 格式");

        ObjectNode strategyProp = props.putObject("strategy");
        strategyProp.put("type", "string");
        ArrayNode enums = mapper.createArrayNode();
        enums.add("driving").add("transit").add("walking").add("riding");
        strategyProp.set("enum", enums);
        strategyProp.put("description", "出行方式：driving = 驾车（默认），transit = 公交地铁，walking = 步行，riding = 共享单车/骑行");

        props.putObject("city")
             .put("type", "string")
             .put("description", "仅 transit 公交地铁策略时必填：所在城市中文名称，例如 北京、上海、广州；如果用户没说，可从起点地址中自动提取出城市名填入");

        parameters.putArray("required").add("origin").add("destination");

        ObjectNode tool = mapper.createObjectNode();
        tool.put("type", "function");
        tool.set("function", function);
        return tool;
    }

    @Override
    public String execute(JsonNode arguments) throws Exception {
        if (apiKey.isEmpty()) {
            return error("高德地图 API Key 未配置。请设置环境变量 AMAP_ROUTE_API_KEY 或 AMAP_WEATHER_API_KEY（和天气工具复用同一个 Key 即可）");
        }
        String origin      = arguments == null ? "" : arguments.path("origin").asText("").trim();
        String destination = arguments == null ? "" : arguments.path("destination").asText("").trim();
        String strategy    = arguments == null ? "driving" : arguments.path("strategy").asText("driving").trim().toLowerCase(Locale.ROOT);
        String city        = arguments == null ? "" : arguments.path("city").asText("").trim();

        if (origin.isEmpty())      return error("origin 起点不能为空");
        if (destination.isEmpty()) return error("destination 终点不能为空");

        switch (strategy) {
            case "driving": case "transit": case "walking": case "riding": break;
            default: strategy = "driving";
        }
        if ("transit".equals(strategy) && city.isEmpty()) {
            city = guessCityFromAddress(origin);
        }

        try {
            String originLngLat      = ensureLngLat(origin, city);
            String destinationLngLat = ensureLngLat(destination, city);

            ObjectNode result;
            switch (strategy) {
                case "transit":
                    if (city == null || city.isBlank()) {
                        return error("transit 公交地铁策略需要 city 参数（所在城市名称），请补充 city 后重试");
                    }
                    result = queryTransit(originLngLat, destinationLngLat, city);
                    break;
                case "walking":
                    result = queryWalkingOrRiding(WALKING_ENDPOINT, originLngLat, destinationLngLat, "walking");
                    break;
                case "riding":
                    result = queryWalkingOrRiding(RIDING_ENDPOINT, originLngLat, destinationLngLat, "riding");
                    break;
                case "driving":
                default:
                    result = queryDriving(originLngLat, destinationLngLat);
                    break;
            }
            result.put("success", true);
            result.put("strategy", strategy);
            result.put("origin", origin);
            result.put("destination", destination);
            addStableRouteEnvelope(result, strategy, origin, destination);
            return mapper.writeValueAsString(result);
        } catch (Exception e) {
            return error("路线规划失败：" + (e.getMessage() == null ? "未知错误" : e.getMessage()));
        }
    }

    private void addStableRouteEnvelope(
        ObjectNode result, String strategy, String origin, String destination
    ) {
        double distance = result.path("total_distance_km").asDouble(0D);
        int duration = result.path("total_duration_minutes").asInt(0);
        String mode = switch (strategy) {
            case "transit" -> "公共交通";
            case "walking" -> "步行";
            case "riding" -> "骑行";
            default -> "驾车";
        };
        String summary = String.format(
            Locale.ROOT,
            "%s到%s推荐%s路线，全程约%.2f公里，预计%d分钟。",
            origin, destination, mode, distance, duration);
        result.put("data_type", "route_plan");
        result.put("display_text", summary);
        ObjectNode routeInfo = result.putObject("route_info");
        routeInfo.put("origin", origin);
        routeInfo.put("destination", destination);
        routeInfo.put("strategy", strategy);
        routeInfo.put("total_distance_km", distance);
        routeInfo.put("total_duration_minutes", duration);
        for (String costField : List.of(
            "taxi_cost_yuan", "toll_cost_yuan", "transit_cost_yuan")) {
            if (result.has(costField)) {
                routeInfo.set(costField, result.path(costField));
            }
        }
        if (result.path("steps").isArray()) {
            routeInfo.set("details", result.path("steps").deepCopy());
        } else if (result.path("segments").isArray()) {
            routeInfo.set("details", result.path("segments").deepCopy());
        }
    }

    // ================= 下面是私有工具方法（不动 API，只在本文件内部使用） =================

    private String ensureLngLat(String addressOrLngLat, String cityHint) throws Exception {
        Matcher m = LNG_LAT.matcher(addressOrLngLat);
        if (m.matches()) {
            try {
                double lng = Double.parseDouble(m.group(1));
                double lat = Double.parseDouble(m.group(2));
                if (lng >= -180 && lng <= 180 && lat >= -90 && lat <= 90) {
                    return formatLngLat(lng, lat);
                }
            } catch (NumberFormatException ignored) { }
        }
        return geocode(addressOrLngLat, cityHint);
    }

    private String geocode(String address, String cityHint) throws Exception {
        StringBuilder url = new StringBuilder(GEOCODE_ENDPOINT)
                .append("?key=").append(encode(apiKey))
                .append("&address=").append(encode(address))
                .append("&output=JSON");
        if (cityHint != null && !cityHint.isBlank()) {
            url.append("&city=").append(encode(cityHint));
        }
        JsonNode root = httpGet(url.toString());
        if (!"1".equals(root.path("status").asText())) {
            throw new RuntimeException("地址解析失败：" + root.path("info").asText("未知错误") + "（地址=" + address + "）");
        }
        JsonNode first = root.path("geocodes").path(0);
        if (first.isMissingNode() || first.isNull()) {
            throw new RuntimeException("找不到地址：" + address + "，请换成更详细的地址重试");
        }
        String location = first.path("location").asText("").trim();
        if (location.isEmpty()) {
            throw new RuntimeException("地址解析失败，未返回经纬度：" + address);
        }
        return location;
    }

    private ObjectNode queryDriving(String origin, String destination) throws Exception {
        String url = DRIVING_ENDPOINT
                + "?key=" + encode(apiKey)
                + "&origin=" + encode(origin)
                + "&destination=" + encode(destination)
                + "&extensions=base"
                + "&output=JSON";
        JsonNode root = httpGet(url);
        assertAmapOk(root, "驾车路线查询");
        JsonNode route = root.path("route").path("paths").path(0);
        if (route.isMissingNode() || route.isNull()) {
            throw new RuntimeException("未找到可行的驾车路线");
        }
        ObjectNode result = mapper.createObjectNode();
        double distance = toDouble(route.path("distance"), 0);
        int duration = (int) Math.round(toDouble(route.path("duration"), 0) / 60d);
        result.put("total_distance_km", Math.round(distance / 10d) / 100d);
        result.put("total_duration_minutes", duration);
        String taxi = route.path("taxi_cost").asText("");
        if (!taxi.isEmpty()) {
            try { result.put("taxi_cost_yuan", Double.parseDouble(taxi)); } catch (NumberFormatException ignored) { }
        }
        String tolls = route.path("tolls").asText("");
        if (!tolls.isEmpty()) {
            try { result.put("toll_cost_yuan", Double.parseDouble(tolls)); } catch (NumberFormatException ignored) { }
        }
        ArrayNode steps = result.putArray("steps");
        JsonNode stepArr = route.path("steps");
        for (JsonNode step : stepArr) {
            ObjectNode s = steps.addObject();
            s.put("instruction", cleanHtml(step.path("instruction").asText("")));
            s.put("road", step.path("road").asText(""));
            double sd = toDouble(step.path("distance"), 0);
            int st = (int) Math.round(toDouble(step.path("duration"), 0) / 60d);
            s.put("distance_km", Math.round(sd / 10d) / 100d);
            s.put("duration_minutes", Math.max(1, st));
        }
        return result;
    }

    private ObjectNode queryTransit(String origin, String destination, String city) throws Exception {
        String url = TRANSIT_ENDPOINT
                + "?key=" + encode(apiKey)
                + "&origin=" + encode(origin)
                + "&destination=" + encode(destination)
                + "&city=" + encode(city)
                + "&cityd=" + encode(city)
                + "&strategy=1"
                + "&output=JSON";
        JsonNode root = httpGet(url);
        assertAmapOk(root, "公交地铁路线查询");
        JsonNode route = root.path("route").path("transits").path(0);
        if (route.isMissingNode() || route.isNull()) {
            throw new RuntimeException("未找到可行的公交地铁路线，请尝试驾车或步行");
        }
        ObjectNode result = mapper.createObjectNode();
        double distance = toDouble(route.path("distance"), 0);
        int duration = (int) Math.round(toDouble(route.path("duration"), 0) / 60d);
        result.put("total_distance_km", Math.round(distance / 10d) / 100d);
        result.put("total_duration_minutes", duration);
        costIfPresent(result, route.path("cost"), "transit_cost_yuan");

        int walkingSec = toInt(route.path("walking_distance"), 0);
        if (walkingSec > 0) result.put("total_walking_meters", walkingSec);

        ArrayNode segments = result.putArray("segments");
        for (JsonNode seg : route.path("segments")) {
            ObjectNode out = segments.addObject();

            JsonNode bus = seg.path("bus").path("buslines").path(0);
            JsonNode railway = seg.path("railway");
            if (!bus.isMissingNode() && !bus.isNull()) {
                out.put("type", "bus/subway");
                out.put("line_name", bus.path("name").asText(""));
                out.put("departure_stop", bus.path("departure_stop").path("name").asText(""));
                out.put("arrival_stop", bus.path("arrival_stop").path("name").asText(""));
                int viaStations = bus.path("via_num").asInt(0);
                out.put("station_count", viaStations + 1);
            } else if (!railway.isMissingNode() && !railway.isNull()) {
                out.put("type", "railway");
                out.put("line_name", railway.path("name").asText(""));
                out.put("departure_stop", railway.path("departure_stop").path("name").asText(""));
                out.put("arrival_stop", railway.path("arrival_stop").path("name").asText(""));
            } else {
                out.put("type", "walking");
            }

            double wd = toDouble(seg.path("walking").path("distance"), 0);
            if (wd > 0) out.put("walking_meters", (int) wd);
        }
        return result;
    }

    private ObjectNode queryWalkingOrRiding(String endpoint, String origin, String destination, String mode) throws Exception {
        String url = endpoint
                + "?key=" + encode(apiKey)
                + "&origin=" + encode(origin)
                + "&destination=" + encode(destination)
                + "&output=JSON";
        JsonNode root = httpGet(url);
        assertAmapOk(root, mode.equals("walking") ? "步行路线查询" : "骑行路线查询");

        JsonNode data = mode.equals("riding") ? root.path("data") : root;
        JsonNode paths = data.path(mode.equals("riding") ? "paths" : "route").path(mode.equals("riding") ? "" : "paths");
        JsonNode route;
        if (mode.equals("riding")) {
            route = paths.path(0);
            if (route.isMissingNode() || route.isNull()) {
                throw new RuntimeException("未找到可行的骑行路线");
            }
        } else {
            route = paths.path(0);
            if (route.isMissingNode() || route.isNull()) {
                throw new RuntimeException("未找到可行的步行路线");
            }
        }

        ObjectNode result = mapper.createObjectNode();
        double distance = toDouble(route.path("distance"), 0);
        int duration = (int) Math.round(toDouble(route.path("duration"), 0) / 60d);
        result.put("total_distance_km", Math.round(distance / 10d) / 100d);
        result.put("total_duration_minutes", duration);
        ArrayNode steps = result.putArray("steps");
        JsonNode stepArr = route.path("steps");
        for (JsonNode step : stepArr) {
            ObjectNode s = steps.addObject();
            String instruction = cleanHtml(step.path("instruction").asText(""));
            if (instruction.isBlank()) instruction = step.path("road").asText("");
            s.put("instruction", instruction);
            s.put("road", step.path("road").asText(""));
            double sd = toDouble(step.path("distance"), 0);
            int st = (int) Math.round(toDouble(step.path("duration"), 0) / 60d);
            s.put("distance_meters", (int) sd);
            s.put("duration_minutes", Math.max(1, st));
        }
        return result;
    }

    private void costIfPresent(ObjectNode node, JsonNode costNode, String key) {
        String cost = costNode.asText("");
        if (!cost.isEmpty()) {
            try { node.put(key, Double.parseDouble(cost)); } catch (NumberFormatException ignored) { }
        }
    }

    private String guessCityFromAddress(String address) {
        if (address == null || address.isBlank()) return "";
        List<String> endings = new ArrayList<>();
        endings.add("市"); endings.add("省"); endings.add("自治区"); endings.add("特别行政区");
        for (String ending : endings) {
            int idx = address.indexOf(ending);
            if (idx > 0) {
                return address.substring(0, idx + ending.length());
            }
        }
        Matcher m = Pattern.compile("^(北京|上海|天津|重庆|广州|深圳|杭州|南京|武汉|成都|西安|苏州|郑州|长沙|青岛|济南|福州|厦门|合肥|南昌|南宁|昆明|贵阳|哈尔滨|长春|沈阳|大连|石家庄|太原|呼和浩特|乌鲁木齐|兰州|西宁|银川|海口|三亚|宁波|无锡|东莞|佛山)").matcher(address);
        if (m.find()) return m.group(1);
        return "";
    }

    private JsonNode httpGet(String url) throws Exception {
        HttpRequest req = HttpRequest.newBuilder().uri(URI.create(url))
                .timeout(requestTimeout).GET().build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (resp.statusCode() != 200) {
            throw new RuntimeException("高德地图接口返回 HTTP " + resp.statusCode());
        }
        return mapper.readTree(resp.body());
    }

    private void assertAmapOk(JsonNode root, String operationName) {
        if (!"1".equals(root.path("status").asText())) {
            String info = root.path("info").asText("未知错误");
            throw new RuntimeException(operationName + "失败：" + info);
        }
    }

    private String error(String message) throws Exception {
        ObjectNode result = mapper.createObjectNode();
        result.put("success", false);
        result.put("error", message);
        result.put("do_not_retry", true);
        result.put("forbidden_tools_fallback", "请不要调用 web_search 或任何其他工具重复查询路线/出行方式！请把上面的 error 字段内容原样回复给用户即可，不要做额外尝试。");
        return mapper.writeValueAsString(result);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    private static String formatLngLat(double lng, double lat) {
        return String.format(Locale.ROOT, "%.6f,%.6f", lng, lat);
    }

    private static double toDouble(JsonNode node, double def) {
        if (node == null || node.isMissingNode() || node.isNull()) return def;
        if (node.isNumber()) return node.asDouble(def);
        try { return Double.parseDouble(node.asText("").trim()); } catch (NumberFormatException e) { return def; }
    }

    private static int toInt(JsonNode node, int def) {
        if (node == null || node.isMissingNode() || node.isNull()) return def;
        if (node.isNumber()) return node.asInt(def);
        try { return Integer.parseInt(node.asText("").trim()); } catch (NumberFormatException e) { return def; }
    }

    private static String cleanHtml(String s) {
        if (s == null) return "";
        return s.replaceAll("<[^>]+>", "").replaceAll("\\s+", " ").trim();
    }

    private static String resolveApiKeyFromEnv() {
        try {
            String v = System.getenv("AMAP_ROUTE_API_KEY");
            if (v != null && !v.isBlank()) return v.trim();
            v = System.getenv("AMAP_WEATHER_API_KEY");
            if (v != null && !v.isBlank()) return v.trim();
            v = System.getenv("AMAP_API_KEY");
            if (v != null && !v.isBlank()) return v.trim();
            v = System.getProperty("AMAP_ROUTE_API_KEY");
            if (v != null && !v.isBlank()) return v.trim();
            v = System.getProperty("amap.route.api-key");
            if (v != null && !v.isBlank()) return v.trim();
        } catch (Exception ignored) { }
        return "";
    }
}
