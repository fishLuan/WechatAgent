package com.clawbot.wechatbot.tools.searchWeatherTool;

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

/**
 * 高德天气工具 — 查询 + 出行/穿衣/洗车晾晒建议。
 * <p>
 * action 取值：get_weather（默认，原始天气）、get_travel_advice（出行建议）、
 * get_dressing_advice（穿衣建议）、get_daily_task_advice（洗车/晾晒）。
 */
public class AmapWeatherTool implements FunctionTool {

    private static final String DEFAULT_ENDPOINT = "https://restapi.amap.com/v3/weather/weatherInfo";
    private static final int SCORE_MAX = 100;
    private static final int SCORE_MIN = 0;

    private final String apiKey;
    private final String endpoint;
    private final HttpClient http;
    private final ObjectMapper mapper;
    private final Duration requestTimeout;

    public AmapWeatherTool(String apiKey) {
        this(apiKey, DEFAULT_ENDPOINT, HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10)).build(), new ObjectMapper(), Duration.ofSeconds(15));
    }

    public AmapWeatherTool(String apiKey, String endpoint, int connectTimeoutSeconds, int requestTimeoutSeconds) {
        this(apiKey, endpoint, HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(connectTimeoutSeconds)).build(),
            new ObjectMapper(), Duration.ofSeconds(requestTimeoutSeconds));
    }

    AmapWeatherTool(String apiKey, String endpoint, HttpClient http, ObjectMapper mapper,
                    Duration requestTimeout) {
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.endpoint = endpoint;
        this.http = http;
        this.mapper = mapper;
        this.requestTimeout = requestTimeout;
    }

    // ==================== FunctionTool ====================

    @Override
    public String name() {
        return "get_weather";
    }

    @Override
    public JsonNode definition() {
        ObjectNode function = mapper.createObjectNode();
        function.put("name", name());
        function.put("description",
            "天气查询与生活建议。action 取 get_weather 返回原始天气数据，"
                + "get_travel_advice 给出出行建议，get_dressing_advice 给出穿衣建议，"
                + "get_daily_task_advice 给出洗车/晾晒建议。默认 get_weather。");
        ObjectNode parameters = function.putObject("parameters");
        parameters.put("type", "object");
        ObjectNode properties = parameters.putObject("properties");
        properties.putObject("city")
            .put("type", "string")
            .put("description", "城市名称，例如：北京、上海、杭州市、西湖区");
        ObjectNode actionProp = properties.putObject("action");
        actionProp.put("type", "string");
        actionProp.set("enum", mapper.createArrayNode()
            .add("get_weather").add("get_travel_advice")
            .add("get_dressing_advice").add("get_daily_task_advice"));
        actionProp.put("description",
            "get_weather=原始天气, get_travel_advice=出行建议, "
                + "get_dressing_advice=穿衣建议, get_daily_task_advice=洗车晾晒。默认 get_weather。");
        // 出行建议专属参数
        properties.putObject("purpose")
            .put("type", "string")
            .put("description", "出行目的（仅 get_travel_advice）：日常出行、户外运动、登山郊游、遛娃、拍照。");
        properties.putObject("gender")
            .put("type", "string")
            .put("description", "性别（仅 get_dressing_advice）：男、女。不填通用。");
        properties.putObject("task")
            .put("type", "string")
            .put("description", "任务类型（仅 get_daily_task_advice）：洗车、晾晒、全部。默认全部。");
        properties.putObject("extensions")
            .put("type", "string")
            .put("description", "仅 get_weather 使用：base=实时, all=预报。默认 base。");
        parameters.putArray("required").add("city");

        ObjectNode tool = mapper.createObjectNode();
        tool.put("type", "function");
        tool.set("function", function);
        return tool;
    }

    @Override
    public String execute(JsonNode arguments) throws Exception {
        if (apiKey.isEmpty()) {
            return error("高德天气 API Key 未配置，请设置 AMAP_WEATHER_API_KEY");
        }
        String city = arguments == null ? "" : arguments.path("city").asText("").trim();
        if (city.isEmpty()) return error("city 参数不能为空");
        String action = arguments == null
            ? "get_weather" : arguments.path("action").asText("get_weather").trim();

        return switch (action) {
            case "get_travel_advice" -> travelAdvice(city, arguments);
            case "get_dressing_advice" -> dressingAdvice(city, arguments);
            case "get_daily_task_advice" -> dailyTaskAdvice(city, arguments);
            default -> rawWeather(city, arguments);
        };
    }

    // ==================== 原始天气 ====================

    private String rawWeather(String city, JsonNode arguments) throws Exception {
        String extensions = arguments == null ? "base" : arguments.path("extensions").asText("base");
        if (!"base".equals(extensions) && !"all".equals(extensions)) extensions = "base";
        JsonNode root = callAmap(city, extensions);
        ObjectNode result = mapper.createObjectNode();
        result.put("success", true);
        result.put("query_city", city);
        result.put("type", extensions);
        if ("all".equals(extensions)) result.set("forecasts", root.path("forecasts"));
        else result.set("lives", root.path("lives"));
        return mapper.writeValueAsString(result);
    }

    // ==================== 出行建议 ====================

    private String travelAdvice(String city, JsonNode arguments) throws Exception {
        String purpose = arguments.path("purpose").asText("日常出行").trim();
        JsonNode live = fetchLive(city);
        if (live == null) return error("未查询到「" + city + "」的天气数据");

        String weather = live.path("weather").asText("");
        double temp = parseDouble(live.path("temperature").asText());
        double humidity = parseDouble(live.path("humidity").asText());
        double wind = parseDouble(live.path("windpower").asText());
        String windDir = live.path("winddirection").asText("");
        String reportTime = live.path("reporttime").asText("");

        int total = scoreWeather(weather) + scoreTemperature(temp) + scoreWindPower(wind) + scoreHumidity(humidity);
        int finalScore = clamp((total + 120) * SCORE_MAX / 190, SCORE_MIN, SCORE_MAX);
        finalScore = adjustByPurpose(finalScore, purpose, weather, wind, temp);

        String level = travelLevel(finalScore);
        String summary = "当前天气「" + weather + "」，温度" + fmtTemp(temp)
            + "，风力" + fmtWind(wind) + "（" + windDir + "），" + level + "「" + purpose + "」。";
        ArrayNode tips = travelTips(weather, temp, wind, humidity, purpose);

        ObjectNode result = mapper.createObjectNode();
        result.put("success", true);
        result.put("city", live.path("city").asText(city));
        result.put("province", live.path("province").asText(""));
        result.put("action", "get_travel_advice");
        result.put("purpose", purpose);
        result.put("score", finalScore);
        result.put("level", level);
        result.put("summary", summary);
        result.put("report_time", reportTime);
        result.set("tips", tips);
        ObjectNode info = result.putObject("weather_info");
        info.put("weather", weather);
        info.put("temperature", fmtTemp(temp));
        info.put("wind", windDir + " " + fmtWind(wind));
        info.put("humidity", humidity + "%");
        return mapper.writeValueAsString(result);
    }

    // ==================== 穿衣建议 ====================

    private String dressingAdvice(String city, JsonNode arguments) throws Exception {
        String gender = arguments.path("gender").asText("").trim();
        JsonNode live = fetchLive(city);
        if (live == null) return error("未查询到「" + city + "」的天气数据");

        String weather = live.path("weather").asText("");
        double temp = parseDouble(live.path("temperature").asText());
        double wind = parseDouble(live.path("windpower").asText());
        double humidity = parseDouble(live.path("humidity").asText());
        String reportTime = live.path("reporttime").asText("");
        String windDir = live.path("winddirection").asText("");

        Clothing upper = upperByTemp(temp);
        Clothing lower = lowerByTemp(temp);
        Clothing shoes = shoesByTemp(temp);

        ArrayNode extras = mapper.createArrayNode();
        ArrayNode outerwear = mapper.createArrayNode();
        boolean needUmbrella = false, needSunscreen = false, needMask = false;
        String w = weather.toLowerCase();

        if (w.contains("雨") || w.contains("rain") || w.contains("shower")) {
            needUmbrella = true;
            extras.add("折叠伞或长柄伞");
            if (!w.contains("小") && !w.contains("light") && !w.contains("drizzle")) {
                outerwear.add("防水外套");
                shoes = new Clothing("防滑防水鞋", "雨天路滑，优先防滑");
            }
        }
        if (w.contains("雪") || w.contains("snow")) {
            needUmbrella = true;
            extras.add("伞（挡雪）");
            outerwear.add("厚羽绒服（防水面料）");
            shoes = new Clothing("防滑雪地靴", "积雪路面防滑防湿");
        }
        if (wind >= 5.0) { outerwear.add("防风外套（风衣或冲锋衣）"); extras.add("围巾（防风灌入）"); }
        if (wind >= 7.0) { extras.add("帽子（防风）"); }
        if ((w.contains("晴") || w.contains("sunny") || w.contains("clear")) && temp > 22.0) {
            needSunscreen = true;
            extras.add("太阳镜");
            if (temp > 28.0) extras.add("遮阳帽");
        }
        if (w.contains("雾") || w.contains("霾") || w.contains("fog") || w.contains("haze")) needMask = true;
        if (humidity > 85.0 && temp > 25.0) { upper = upperByTemp(temp + 3.0); lower = lowerByTemp(temp + 3.0); }
        if (temp < 10.0) extras.add("手套");

        String prefix = gender.isEmpty() ? "建议" : ("建议" + gender + "性");
        String summary = "今日天气「" + weather + "」，气温" + fmtTemp(temp) + "。"
            + prefix + "上装：" + upper.name + "；下装：" + lower.name + "；鞋子：" + shoes.name + "。";
        if (needUmbrella) summary += "出门请带伞。";
        if (needSunscreen) summary += "注意防晒。";
        if (needMask) summary += "建议戴口罩。";

        ObjectNode result = mapper.createObjectNode();
        result.put("success", true);
        result.put("city", live.path("city").asText(city));
        result.put("province", live.path("province").asText(""));
        result.put("action", "get_dressing_advice");
        result.put("report_time", reportTime);
        result.put("summary", summary);
        ObjectNode winfo = result.putObject("weather_info");
        winfo.put("weather", weather);
        winfo.put("temperature", fmtTemp(temp));
        winfo.put("humidity", humidity + "%");
        winfo.put("wind", windDir + " " + fmtWind(wind));
        ObjectNode dressing = result.putObject("dressing");
        dressing.put("upper", upper.name).put("upper_tip", upper.tip);
        dressing.put("lower", lower.name).put("lower_tip", lower.tip);
        dressing.put("shoes", shoes.name).put("shoes_tip", shoes.tip);
        if (outerwear.size() > 0) dressing.set("outerwear", outerwear);
        if (extras.size() > 0) dressing.set("extras", extras);
        dressing.put("need_umbrella", needUmbrella);
        dressing.put("need_sunscreen", needSunscreen);
        dressing.put("need_mask", needMask);
        return mapper.writeValueAsString(result);
    }

    // ==================== 洗车 / 晾晒 ====================

    private String dailyTaskAdvice(String city, JsonNode arguments) throws Exception {
        String task = arguments.path("task").asText("全部").trim();
        JsonNode casts = fetchForecastCasts(city);
        if (casts == null) return error("未查询到「" + city + "」的天气预报数据");

        JsonNode fc = fetchForecastRoot(city);
        ObjectNode result = mapper.createObjectNode();
        result.put("success", true);
        result.put("action", "get_daily_task_advice");
        result.put("city", fc.path("city").asText(city));
        result.put("province", fc.path("province").asText(""));

        ArrayNode results = result.putArray("advice");
        if ("洗车".equals(task) || "全部".equals(task)) results.add(carWashAdvice(casts));
        if ("晾晒".equals(task) || "全部".equals(task)) results.add(dryLaundryAdvice(casts));
        return mapper.writeValueAsString(result);
    }

    private ObjectNode carWashAdvice(JsonNode casts) {
        boolean rain24h = false, rain48h = false;
        String rainDay = "";
        for (int i = 1; i <= 2 && i < casts.size(); i++) {
            JsonNode c = casts.get(i);
            if (isRain(c.path("dayweather").asText("")) || isRain(c.path("nightweather").asText(""))) {
                if (i == 1) rain24h = true;
                rain48h = true;
                if (rainDay.isEmpty()) rainDay = c.path("date").asText(dayLabel(i));
            }
        }
        int score; String level; String summary; ArrayNode tips = mapper.createArrayNode();
        if (rain24h) {
            score = 10; level = "不适宜";
            summary = rainDay + "有降雨，强烈不建议洗车。";
            tips.add("🚫 " + rainDay + "有雨，今天别洗车");
        } else if (rain48h) {
            score = 35; level = "不太适宜";
            summary = rainDay + "有降雨，洗车保持不了多久。";
            tips.add("⚠️ " + rainDay + "有雨，非要洗就停地库");
        } else {
            boolean rain72h = casts.size() > 3 && (isRain(casts.get(3).path("dayweather").asText(""))
                || isRain(casts.get(3).path("nightweather").asText("")));
            if (rain72h) {
                score = 70; level = "较适宜";
                summary = "未来 48h 无雨，但大后天有降雨。";
                tips.add("✅ 今明两天无雨，适合洗车");
                tips.add("📅 大后天可能有雨");
            } else {
                score = 90; level = "非常适宜";
                summary = "未来几天均无降雨，非常适合洗车。";
                tips.add("✅ 未来几天无雨，放心洗车");
            }
        }
        ObjectNode node = mapper.createObjectNode();
        node.put("task", "洗车"); node.put("score", score);
        node.put("level", level); node.put("summary", summary);
        node.set("tips", tips);
        return node;
    }

    private ObjectNode dryLaundryAdvice(JsonNode casts) {
        int consecutive = 0, maxConsecutive = 0;
        boolean rainTomorrow = false, strongWind = false;
        ArrayNode forecastDays = mapper.createArrayNode();
        for (int i = 1; i <= 3 && i < casts.size(); i++) {
            JsonNode c = casts.get(i);
            String dw = c.path("dayweather").asText("");
            double dp = parseDouble(c.path("daypower").asText("0"));
            double dt = parseDouble(c.path("daytemp").asText("20"));
            boolean good = !isRain(dw) && dp < 6.0 && !dw.contains("阴") && !dw.contains("overcast") && dt >= 0;
            if (i == 1 && (isRain(dw) || isRain(c.path("nightweather").asText("")))) rainTomorrow = true;
            if (dp >= 5.0) strongWind = true;
            ObjectNode dn = mapper.createObjectNode();
            dn.put("date", c.path("date").asText(dayLabel(i)));
            dn.put("day_weather", dw);
            dn.put("day_temp", fmtTemp(dt));
            dn.put("wind_power", fmtWind(dp));
            dn.put("suitable", good);
            forecastDays.add(dn);
            if (good) { consecutive++; maxConsecutive = Math.max(maxConsecutive, consecutive); }
            else consecutive = 0;
        }
        int score; String level; String summary; ArrayNode tips = mapper.createArrayNode();
        if (rainTomorrow) {
            score = 5; level = "不适宜";
            summary = "明天有降雨，不建议晾晒。";
            tips.add("🚫 明天有雨，别晾晒");
        } else if (maxConsecutive >= 3 && !strongWind) {
            score = 90; level = "非常适宜";
            summary = "未来连续" + maxConsecutive + "天晴好，非常适合晾晒被褥衣物。";
            tips.add("☀️ 连续晴好天气，适合大规模晾晒");
            tips.add("🧺 可以洗床单被套，几天都能干透");
        } else if (maxConsecutive >= 2) {
            score = 70; level = "适宜";
            summary = "未来有" + maxConsecutive + "天晴好，可以晾晒。";
            tips.add("✅ 晴好天气够用，适合晾晒日常衣物");
        } else if (maxConsecutive >= 1) {
            score = 45; level = "一般";
            summary = "晴天不多，建议晾晒轻薄衣物。";
            tips.add("⚠️ 晴天窗口有限，建议晾轻薄衣物");
        } else {
            score = 10; level = "不适宜";
            summary = "未来几天天气不佳，不建议户外晾晒。";
            tips.add("🚫 天气不佳，建议用烘干机或室内晾晒");
        }
        if (strongWind && maxConsecutive >= 1) tips.add("💨 风力较大，晾晒时夹紧衣物防吹落");
        double tomorrowTemp = casts.size() > 1 ? parseDouble(casts.get(1).path("daytemp").asText("10")) : 20;
        if (tomorrowTemp < 5 && maxConsecutive >= 1) tips.add("❄️ 气温低，衣物可能冻结，建议室内晾晒");
        ObjectNode node = mapper.createObjectNode();
        node.put("task", "晾晒"); node.put("score", score);
        node.put("level", level); node.put("summary", summary);
        node.put("consecutive_good_days", maxConsecutive);
        node.set("forecast_days", forecastDays);
        node.set("tips", tips);
        return node;
    }

    // ==================== 天气 API ====================

    private JsonNode callAmap(String city, String extensions) throws Exception {
        String url = endpoint + "?key=" + encode(apiKey)
            + "&city=" + encode(city) + "&extensions=" + extensions + "&output=JSON";
        HttpRequest request = HttpRequest.newBuilder().uri(URI.create(url))
            .timeout(requestTimeout).GET().build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() != 200)
            throw new RuntimeException("高德天气接口返回 HTTP " + response.statusCode());
        JsonNode root = mapper.readTree(response.body());
        if (!"1".equals(root.path("status").asText()))
            throw new RuntimeException("高德天气查询失败：" + root.path("info").asText("未知错误"));
        return root;
    }

    private JsonNode fetchLive(String city) throws Exception {
        JsonNode lives = callAmap(city, "base").path("lives");
        return lives.isArray() && !lives.isEmpty() ? lives.get(0) : null;
    }

    private JsonNode fetchForecastRoot(String city) throws Exception {
        JsonNode forecasts = callAmap(city, "all").path("forecasts");
        return forecasts.isArray() && !forecasts.isEmpty() ? forecasts.get(0) : null;
    }

    private JsonNode fetchForecastCasts(String city) throws Exception {
        JsonNode fc = fetchForecastRoot(city);
        if (fc == null) return null;
        JsonNode casts = fc.path("casts");
        return casts.isArray() && !casts.isEmpty() ? casts : null;
    }

    // ==================== 出行评分 ====================

    private int scoreWeather(String weather) {
        if (weather.isEmpty()) return 0;
        String w = weather.toLowerCase();
        if (w.contains("晴") || w.contains("sunny") || w.contains("clear")) return 25;
        if (w.contains("多云") || w.contains("cloudy")) return 15;
        if (w.contains("阴") || w.contains("overcast")) return 5;
        if (w.contains("阵雨") || w.contains("shower")) return -10;
        if (w.contains("小雨") || w.contains("light rain") || w.contains("drizzle")) return -10;
        if (w.contains("中雨") || w.contains("moderate rain")) return -20;
        if (w.contains("大雨") || w.contains("雷阵雨") || w.contains("thunder")) return -30;
        if (w.contains("暴雨") || w.contains("storm") || w.contains("heavy")) return -40;
        if (w.contains("雪") || w.contains("snow")) return -35;
        if (w.contains("雾") || w.contains("fog") || w.contains("霾") || w.contains("haze")) return -25;
        if (w.contains("沙尘") || w.contains("sand") || w.contains("dust")) return -35;
        return -5;
    }

    private int scoreTemperature(double temp) {
        if (temp == 0.0) return 0;
        if (temp >= 15.0 && temp <= 28.0) return 25;
        if ((temp >= 10.0 && temp < 15.0) || (temp > 28.0 && temp <= 33.0)) return 10;
        if ((temp >= 5.0 && temp < 10.0) || (temp > 33.0 && temp <= 36.0)) return -5;
        if (temp >= 0.0 && temp < 5.0) return -15;
        if (temp > 36.0) return -25;
        return -30;
    }

    private int scoreWindPower(double power) {
        if (power == 0.0) return 0;
        if (power <= 3.0) return 10;
        if (power <= 5.0) return -10;
        if (power <= 7.0) return -25;
        return -30;
    }

    private int scoreHumidity(double humidity) {
        if (humidity == 0.0) return 0;
        if (humidity >= 40.0 && humidity <= 70.0) return 10;
        if ((humidity >= 30.0 && humidity < 40.0) || (humidity > 70.0 && humidity <= 85.0)) return 0;
        return -10;
    }

    private int adjustByPurpose(int score, String purpose, String weather, double windPower, double temperature) {
        return switch (purpose) {
            case "户外运动" -> score - (windPower > 5 ? 15 : 0) - (temperature > 35 ? 15 : 0)
                - (weather.contains("雨") || weather.contains("rain") ? 20 : 0);
            case "登山郊游" -> score - ((weather.contains("雨") || weather.contains("rain")
                || weather.contains("雪") || weather.contains("snow")) ? 30 : 0)
                - (windPower > 5 ? 25 : 0) - (weather.contains("雾") || weather.contains("fog") ? 20 : 0);
            case "遛娃" -> score - (temperature < 5 || temperature > 35 ? 20 : 0)
                - (weather.contains("雨") || weather.contains("rain") ? 25 : 0)
                - (windPower > 5 ? 15 : 0);
            case "拍照" -> score + (weather.contains("晴") || weather.contains("sunny") ? 10 : 0)
                + (weather.contains("阴") || weather.contains("overcast") ? 5 : 0)
                - (weather.contains("雨") || weather.contains("rain")
                || weather.contains("雪") || weather.contains("snow") ? 20 : 0);
            default -> score;
        };
    }

    private String travelLevel(int score) {
        if (score >= 80) return "非常适宜";
        if (score >= 65) return "适宜";
        if (score >= 50) return "较适宜";
        if (score >= 35) return "一般";
        if (score >= 20) return "不太适宜";
        return "不适宜";
    }

    private ArrayNode travelTips(String weather, double temp, double wind, double humidity, String purpose) {
        ArrayNode tips = mapper.createArrayNode();
        String w = weather.toLowerCase();
        if (w.contains("雨") || w.contains("rain") || w.contains("shower") || w.contains("drizzle"))
            tips.add("☔ 有降雨，出门请带好雨具");
        if (w.contains("晴") && temp > 25.0) tips.add("☀️ 天气晴朗温度较高，注意防晒和补水");
        if (temp < 5.0) tips.add("🧣 天气寒冷，请穿厚外套、围巾保暖");
        else if (temp < 15.0) tips.add("🧥 温度偏低，建议穿外套或毛衣");
        if (temp > 28.0) tips.add("💧 温度较高，注意多喝水和防暑降温");
        if (wind > 5.0) tips.add("💨 风力较大，注意防风，远离广告牌和临时搭建物");
        if (humidity > 85.0) tips.add("💦 湿度较高，体感可能闷热，注意通风");
        if (w.contains("雾") || w.contains("霾") || w.contains("fog") || w.contains("haze"))
            tips.add("😷 能见度较低，驾车请减速慢行，建议戴口罩");
        if ("户外运动".equals(purpose) && temp > 32.0) tips.add("🏃 高温下运动易中暑，建议选择清晨或傍晚时段");
        if ("登山郊游".equals(purpose)) tips.add("🎒 山区天气多变，建议带一件防风外套");
        if ("遛娃".equals(purpose) && (w.contains("晴") || w.contains("sunny"))) tips.add("👶 晴天户外活动注意防晒，给宝宝戴帽子");
        if (tips.isEmpty()) tips.add("✅ 天气状况良好，适合出行");
        return tips;
    }

    // ==================== 穿衣映射 ====================

    private record Clothing(String name, String tip) {}

    private Clothing upperByTemp(double temp) {
        if (temp <= -10.0) return new Clothing("厚羽绒服 + 高领毛衣 + 保暖内衣", "极寒天气，三层叠穿");
        if (temp <= 0.0) return new Clothing("羽绒服 + 毛衣 + 保暖内衣", "零下注意叠穿保暖");
        if (temp <= 5.0) return new Clothing("厚棉服或薄羽绒服 + 毛衣", "室外较冷，建议带围巾");
        if (temp <= 10.0) return new Clothing("呢大衣或冲锋衣 + 卫衣/薄毛衣", "早晚偏凉，内搭长袖T恤");
        if (temp <= 15.0) return new Clothing("风衣/牛仔外套/薄呢外套 + 长袖T恤", "春秋过渡，叠穿最灵活");
        if (temp <= 20.0) return new Clothing("薄外套或开衫 + 长袖衬衫/T恤", "备一件薄外套应对早晚温差");
        if (temp <= 25.0) return new Clothing("长袖T恤或薄衬衫，可备一件薄外套", "温度宜人");
        if (temp <= 30.0) return new Clothing("短袖T恤、POLO衫、连衣裙", "夏装为主");
        if (temp <= 35.0) return new Clothing("轻薄短袖/吊带/背心 + 防晒衫", "炎热天气，注意防晒");
        return new Clothing("轻薄透气短袖/吊带 + 防晒衫+遮阳帽", "酷热高温，减少户外暴晒");
    }

    private Clothing lowerByTemp(double temp) {
        if (temp <= -10.0) return new Clothing("加绒棉裤或羽绒裤 + 保暖秋裤", "极寒保暖优先");
        if (temp <= 0.0) return new Clothing("加绒长裤或厚牛仔裤 + 保暖秋裤", "零下叠穿两层");
        if (temp <= 5.0) return new Clothing("厚长裤（加绒/毛呢）+ 秋裤", "长裤要够厚");
        if (temp <= 10.0) return new Clothing("牛仔裤或休闲长裤 + 薄秋裤", "视耐寒程度加秋裤");
        if (temp <= 15.0) return new Clothing("牛仔裤/休闲裤/长裙", "单层长裤即可");
        if (temp <= 20.0) return new Clothing("薄长裤/牛仔裤/过膝裙", "舒适温度");
        if (temp <= 25.0) return new Clothing("薄长裤/九分裤/中长裙/短裤", "温暖舒适");
        if (temp <= 30.0) return new Clothing("短裤/短裙/薄长裤", "夏天穿搭");
        if (temp <= 35.0) return new Clothing("短裤/短裙/阔腿裤", "宽松轻薄为主");
        return new Clothing("超短裤/短裙/轻薄阔腿裤", "酷热，选最透气的");
    }

    private Clothing shoesByTemp(double temp) {
        if (temp <= -10.0) return new Clothing("加绒雪地靴或厚棉靴", "极寒保暖优先");
        if (temp <= 0.0) return new Clothing("雪地靴或加绒马丁靴", "保暖+防滑");
        if (temp <= 5.0) return new Clothing("棉靴/加绒短靴", "脚暖全身暖");
        if (temp <= 10.0) return new Clothing("皮鞋/马丁靴/短靴", "深秋或初春穿搭");
        if (temp <= 15.0) return new Clothing("运动鞋/帆布鞋/乐福鞋", "春秋百搭");
        if (temp <= 20.0) return new Clothing("运动鞋/板鞋/单鞋", "舒适轻便");
        if (temp <= 25.0) return new Clothing("运动鞋/帆布鞋/乐福鞋/浅口单鞋", "透气舒适");
        if (temp <= 30.0) return new Clothing("网面运动鞋/帆布鞋/凉鞋", "夏天透气款");
        if (temp <= 35.0) return new Clothing("凉鞋/拖鞋/网面鞋", "越透气越好");
        return new Clothing("凉鞋/拖鞋", "酷热天气");
    }

    // ==================== 通用工具 ====================

    private boolean isRain(String weather) {
        if (weather == null || weather.isBlank()) return false;
        String w = weather.toLowerCase();
        return w.contains("雨") || w.contains("rain") || w.contains("雪")
            || w.contains("snow") || w.contains("shower") || w.contains("drizzle")
            || w.contains("storm");
    }

    private double parseDouble(String text) {
        if (text == null || text.isBlank()) return 0.0;
        try {
            return Double.parseDouble(text.replace("℃", "").replace("°C", "")
                .replace("级", "").replace("%", "").trim());
        } catch (NumberFormatException ignored) { return 0.0; }
    }

    private String fmtTemp(double t) {
        if (t == 0.0) return "未知";
        return ((int) t == t ? String.valueOf((int) t) : String.valueOf(t)) + "°C";
    }

    private String fmtWind(double p) {
        if (p == 0.0) return "微风";
        return ((int) p == p ? String.valueOf((int) p) : String.valueOf(p)) + "级";
    }

    private int clamp(int v, int min, int max) { return Math.max(min, Math.min(max, v)); }

    private String dayLabel(int offset) {
        return switch (offset) { case 1 -> "明天"; case 2 -> "后天"; case 3 -> "大后天"; default -> "第" + offset + "天"; };
    }

    private String error(String message) throws Exception {
        ObjectNode result = mapper.createObjectNode();
        result.put("success", false);
        result.put("error", message);
        return mapper.writeValueAsString(result);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
