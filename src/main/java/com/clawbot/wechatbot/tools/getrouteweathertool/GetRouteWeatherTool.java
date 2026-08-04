package com.clawbot.wechatbot.tools.getrouteweathertool;

import com.clawbot.wechatbot.tools.FunctionTool;
import com.clawbot.wechatbot.tools.pathplantool.PathPlanTool;
import com.clawbot.wechatbot.tools.searchWeatherTool.AmapWeatherTool;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Set;

/**
 * 路线天气工具：规划路线的同时，查询起点和终点的天气，给出出行综合建议。
 * <p>
 * 内部组合调用 {@link PathPlanTool} 和 {@link AmapWeatherTool}，
 * 一次调用即可拿到完整路线 + 两端天气 + 出行提示。
 */
public class GetRouteWeatherTool implements FunctionTool {

    /** 中国地级市/直辖市列表，用于从地址中提取城市名。 */
    private static final Set<String> KNOWN_CITIES = Set.of(
        "北京", "北京市", "上海", "上海市", "天津", "天津市", "重庆", "重庆市",
        "杭州", "杭州市", "宁波", "宁波市", "温州", "温州市", "嘉兴", "嘉兴市", "湖州", "湖州市",
        "绍兴", "绍兴市", "金华", "金华市", "衢州", "衢州市", "舟山", "舟山市", "台州", "台州市", "丽水", "丽水市",
        "南京", "南京市", "苏州", "苏州市", "无锡", "无锡市", "常州", "常州市", "南通", "南通市",
        "扬州", "扬州市", "镇江", "镇江市", "泰州", "泰州市", "盐城", "盐城市", "淮安", "淮安市",
        "连云港", "连云港市", "徐州", "徐州市", "宿迁", "宿迁市",
        "广州", "广州市", "深圳", "深圳市", "东莞", "东莞市", "佛山", "佛山市",
        "珠海", "珠海市", "中山", "中山市", "惠州", "惠州市", "江门", "江门市",
        "成都", "成都市", "绵阳", "绵阳市", "德阳", "德阳市",
        "武汉", "武汉市", "宜昌", "宜昌市", "襄阳", "襄阳市",
        "长沙", "长沙市", "株洲", "株洲市", "湘潭", "湘潭市",
        "郑州", "郑州市", "洛阳", "洛阳市", "开封", "开封市",
        "济南", "济南市", "青岛", "青岛市", "烟台", "烟台市", "威海", "威海市",
        "西安", "西安市", "咸阳", "咸阳市",
        "福州", "福州市", "厦门", "厦门市", "泉州", "泉州市",
        "合肥", "合肥市", "芜湖", "芜湖市",
        "南昌", "南昌市", "九江", "九江市",
        "贵阳", "贵阳市", "遵义", "遵义市",
        "昆明", "昆明市", "大理", "大理市",
        "南宁", "南宁市", "桂林", "桂林市", "柳州", "柳州市",
        "海口", "海口市", "三亚", "三亚市",
        "哈尔滨", "哈尔滨市", "齐齐哈尔", "齐齐哈尔市",
        "长春", "长春市", "吉林", "吉林市",
        "沈阳", "沈阳市", "大连", "大连市",
        "石家庄", "石家庄市", "唐山", "唐山市", "保定", "保定市",
        "太原", "太原市", "大同", "大同市",
        "呼和浩特", "呼和浩特市", "包头", "包头市",
        "兰州", "兰州市", "天水", "天水市",
        "银川", "银川市", "西宁", "西宁市", "拉萨", "拉萨市", "乌鲁木齐", "乌鲁木齐市"
    );

    private final PathPlanTool pathTool;
    private final AmapWeatherTool weatherTool;
    private final ObjectMapper mapper;

    public GetRouteWeatherTool(PathPlanTool pathTool, AmapWeatherTool weatherTool,
                               ObjectMapper mapper) {
        this.pathTool = pathTool;
        this.weatherTool = weatherTool;
        this.mapper = mapper;
    }

    @Override
    public String name() {
        return "get_route_weather";
    }

    @Override
    public JsonNode definition() {
        ObjectNode function = mapper.createObjectNode();
        function.put("name", name());
        function.put("description",
            "规划出行路线并同时查询起点和终点天气，给出综合出行建议。"
                + "一次调用即可获得：路线距离耗时 + 两端天气 + 是否需要带伞/防晒/防风提示。"
                + "支持驾车、公交地铁、步行、骑行四种方式。");
        ObjectNode parameters = function.putObject("parameters");
        parameters.put("type", "object");
        ObjectNode props = parameters.putObject("properties");
        props.putObject("origin")
            .put("type", "string")
            .put("description", "起点地址，例如：北京西站、杭州市西湖区");
        props.putObject("destination")
            .put("type", "string")
            .put("description", "终点地址，例如：天安门广场、上海虹桥火车站");
        ObjectNode strategyProp = props.putObject("strategy");
        strategyProp.put("type", "string");
        ArrayNode enums = mapper.createArrayNode();
        enums.add("driving").add("transit").add("walking").add("riding");
        strategyProp.set("enum", enums);
        strategyProp.put("description", "出行方式：driving=驾车, transit=公交地铁, walking=步行, riding=骑行。默认 driving。");
        parameters.putArray("required").add("origin").add("destination");

        ObjectNode tool = mapper.createObjectNode();
        tool.put("type", "function");
        tool.set("function", function);
        return tool;
    }

    @Override
    public String execute(JsonNode arguments) throws Exception {
        String origin = arguments == null ? "" : arguments.path("origin").asText("").trim();
        String destination = arguments == null ? "" : arguments.path("destination").asText("").trim();
        String strategy = arguments == null
            ? "driving" : arguments.path("strategy").asText("driving").trim();

        if (origin.isEmpty()) return error("origin 起点不能为空");
        if (destination.isEmpty()) return error("destination 终点不能为空");

        // ---- 1. 调用路线规划 ----
        ObjectNode routeArgs = mapper.createObjectNode();
        routeArgs.put("origin", origin);
        routeArgs.put("destination", destination);
        routeArgs.put("strategy", strategy);
        String routeResult = pathTool.execute(routeArgs);
        JsonNode route = mapper.readTree(routeResult);
        if (!route.path("success").asBoolean(false)) {
            return error("路线规划失败：" + route.path("error").asText("未知错误"));
        }

        // ---- 2. 提取城市 ----
        String originCity = extractCity(origin);
        String destCity = extractCity(destination);

        // ---- 3. 并行查天气 ----
        ObjectNode weatherArgs = mapper.createObjectNode();
        weatherArgs.put("extensions", "base");

        weatherArgs.put("city", originCity);
        JsonNode originWeather = mapper.readTree(weatherTool.execute(weatherArgs));

        weatherArgs.put("city", destCity);
        JsonNode destWeather = mapper.readTree(weatherTool.execute(weatherArgs));

        // ---- 4. 组装结果 ----
        ObjectNode result = mapper.createObjectNode();
        result.put("success", true);
        result.put("strategy", strategy);

        // 路线摘要
        ObjectNode routeSummary = result.putObject("route");
        routeSummary.put("origin", origin);
        routeSummary.put("destination", destination);
        copyIfExists(route, routeSummary, "total_distance_km");
        copyIfExists(route, routeSummary, "total_duration_minutes");
        copyIfExists(route, routeSummary, "taxi_cost_yuan");
        copyIfExists(route, routeSummary, "toll_cost_yuan");
        copyIfExists(route, routeSummary, "total_walking_meters");

        // 起点天气
        result.set("origin_weather", buildWeatherNode(originWeather, originCity));

        // 终点天气
        result.set("destination_weather", buildWeatherNode(destWeather, destCity));

        // ---- 5. 综合出行建议 ----
        ArrayNode tips = mapper.createArrayNode();
        boolean sameCity = originCity.equals(destCity);

        String ow = extractWeatherText(originWeather);
        String dw = extractWeatherText(destWeather);

        if (sameCity) {
            // 同城出行
            if (hasRain(ow)) {
                tips.add("☔ " + originCity + "正在下雨，建议带伞并注意路面湿滑");
                if ("walking".equals(strategy) || "riding".equals(strategy)) {
                    tips.add("🚇 雨天步行/骑行不便，建议改乘公交或地铁");
                }
            }
            if (hasStrongWind(originWeather)) {
                tips.add("💨 " + originCity + "风力较大，骑行/步行请注意防风");
            }
        } else {
            // 跨城出行
            if (hasRain(ow)) {
                tips.add("☔ 出发地「" + originCity + "」有雨，出发时请带伞");
            }
            if (hasRain(dw)) {
                tips.add("☔ 目的地「" + destCity + "」有雨，到达后可能需要雨具");
            }
            if (!hasRain(ow) && !hasRain(dw)) {
                tips.add("✅ 起点和终点均无雨，天气适合出行");
            }
            // 温差提醒
            double ot = extractTemp(originWeather);
            double dt = extractTemp(destWeather);
            if (Math.abs(ot - dt) > 10.0) {
                tips.add("🌡️ 两地温差较大（" + formatTemp(ot) + " → " + formatTemp(dt) + "），建议带一件外套备换");
            }
        }

        // 通用建议
        if (!sameCity && "driving".equals(strategy)) {
            tips.add("🚗 长途驾车注意休息，关注沿途天气变化");
        }
        if ("transit".equals(strategy)) {
            tips.add("🚌 公交/地铁出行受天气影响较小，是雨天的好选择");
        }

        if (tips.isEmpty()) {
            tips.add("✅ 天气状况良好，祝出行顺利！");
        }
        result.set("tips", tips);

        return mapper.writeValueAsString(result);
    }

    // ==================== 天气解析 ====================

    private ObjectNode buildWeatherNode(JsonNode weatherJson, String city) {
        ObjectNode node = mapper.createObjectNode();
        if (!weatherJson.path("success").asBoolean(false)) {
            node.put("city", city);
            node.put("available", false);
            node.put("error", weatherJson.path("error").asText("查询失败"));
            return node;
        }
        JsonNode live = weatherJson.path("lives").get(0);
        if (live == null) {
            node.put("city", city);
            node.put("available", false);
            return node;
        }
        node.put("city", live.path("city").asText(city));
        node.put("province", live.path("province").asText(""));
        node.put("weather", live.path("weather").asText(""));
        node.put("temperature", live.path("temperature").asText("") + "°C");
        node.put("wind", live.path("winddirection").asText("") + " "
            + live.path("windpower").asText("") + "级");
        node.put("humidity", live.path("humidity").asText("") + "%");
        node.put("report_time", live.path("reporttime").asText(""));
        return node;
    }

    private String extractWeatherText(JsonNode weatherJson) {
        JsonNode live = weatherJson.path("lives").get(0);
        if (live == null) return "";
        return live.path("weather").asText("");
    }

    private double extractTemp(JsonNode weatherJson) {
        JsonNode live = weatherJson.path("lives").get(0);
        if (live == null) return 0;
        return parseDouble(live.path("temperature").asText("0"));
    }

    private boolean hasRain(String weather) {
        String w = weather.toLowerCase();
        return w.contains("雨") || w.contains("rain")
            || w.contains("雪") || w.contains("snow")
            || w.contains("shower") || w.contains("storm");
    }

    private boolean hasStrongWind(JsonNode weatherJson) {
        JsonNode live = weatherJson.path("lives").get(0);
        if (live == null) return false;
        return parseDouble(live.path("windpower").asText("0")) >= 5.0;
    }

    // ==================== 城市提取 ====================

    /** 从地址中提取城市名。 */
    private String extractCity(String address) {
        if (address == null || address.isBlank()) return "";
        // 先匹配省前缀：黑龙江省哈尔滨市 → 哈尔滨
        String s = address.trim();
        // 去掉省/自治区前缀
        s = s.replaceFirst("^.{1,6}?(省|自治区)", "");
        // 匹配已知城市
        for (String city : KNOWN_CITIES) {
            if (s.startsWith(city)) {
                // 返回不带"市"后缀的纯城市名
                return city.endsWith("市") ? city.substring(0, city.length() - 1) : city;
            }
        }
        // 兜底：取前两个字试试
        if (s.length() >= 2) {
            String prefix = s.substring(0, 2);
            if (KNOWN_CITIES.contains(prefix) || KNOWN_CITIES.contains(prefix + "市")) {
                return prefix;
            }
        }
        return "";
    }

    // ==================== 工具方法 ====================

    private void copyIfExists(JsonNode source, ObjectNode target, String field) {
        JsonNode value = source.path(field);
        if (!value.isMissingNode() && !value.isNull()) {
            if (value.isNumber()) target.put(field, value.asDouble());
            else target.put(field, value.asText());
        }
    }

    private double parseDouble(String text) {
        if (text == null || text.isBlank()) return 0.0;
        try {
            return Double.parseDouble(text.replace("℃", "").replace("°C", "")
                .replace("级", "").replace("%", "").trim());
        } catch (NumberFormatException ignored) {
            return 0.0;
        }
    }

    private String formatTemp(double temp) {
        return ((int) temp == temp ? String.valueOf((int) temp) : String.valueOf(temp)) + "°C";
    }

    private String error(String message) throws Exception {
        ObjectNode result = mapper.createObjectNode();
        result.put("success", false);
        result.put("error", message);
        return mapper.writeValueAsString(result);
    }
}
