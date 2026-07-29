package com.clawbot.wechatbot.tools.calculatezodiacinfotool;

import com.clawbot.wechatbot.tools.FunctionTool;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.MonthDay;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 公历生日 → 生肖（按农历春节分界）+ 星座 + 周岁 + 虚岁 综合计算器。
 *
 * 纯本地计算，不调用任何外部 API：
 *   1. 生肖：以农历正月初一（春节）为界，不是公历 1 月 1 日；自动处理 1900~2100 年春节日期
 *   2. 星座：精确占星学日期范围（黄道十二宫标准边界）
 *   3. 周岁：生日未到减一岁（国际通用实岁算法）
 *   4. 虚岁：中国传统算法——出生即 1 岁，每过一个农历春节长 1 岁
 *   5. 附加：下次生日倒计时、本命年判断、生肖五行/别称、星座元素/守护星/幸运色/性格关键词
 */
public class CalculateZodiacInfoTool implements FunctionTool {

    private static final ZoneId DEFAULT_TZ = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter DATE_FMT_CN = DateTimeFormatter.ofPattern("yyyy 年 M 月 d 日", Locale.CHINA);

    // ========== 1900~2100 年农历春节（正月初一）对应的公历日期表 ==========
    // 来源：中国科学院紫金山天文台《万年历》权威数据，key=公历年，value=当年春节（正月初一）的月-日
    private static final Map<Integer, MonthDay> SPRING_FESTIVAL_TABLE = Map.ofEntries(
        Map.entry(1900, MonthDay.of(1, 31)), Map.entry(1901, MonthDay.of(2, 19)), Map.entry(1902, MonthDay.of(2, 8)),
        Map.entry(1903, MonthDay.of(1, 29)), Map.entry(1904, MonthDay.of(2, 16)), Map.entry(1905, MonthDay.of(2, 4)),
        Map.entry(1906, MonthDay.of(1, 25)), Map.entry(1907, MonthDay.of(2, 13)), Map.entry(1908, MonthDay.of(2, 2)),
        Map.entry(1909, MonthDay.of(1, 22)), Map.entry(1910, MonthDay.of(2, 10)), Map.entry(1911, MonthDay.of(1, 30)),
        Map.entry(1912, MonthDay.of(2, 18)), Map.entry(1913, MonthDay.of(2, 6)), Map.entry(1914, MonthDay.of(1, 26)),
        Map.entry(1915, MonthDay.of(2, 14)), Map.entry(1916, MonthDay.of(2, 3)), Map.entry(1917, MonthDay.of(1, 23)),
        Map.entry(1918, MonthDay.of(2, 11)), Map.entry(1919, MonthDay.of(2, 1)), Map.entry(1920, MonthDay.of(2, 20)),
        Map.entry(1921, MonthDay.of(2, 8)), Map.entry(1922, MonthDay.of(1, 28)), Map.entry(1923, MonthDay.of(2, 16)),
        Map.entry(1924, MonthDay.of(2, 5)), Map.entry(1925, MonthDay.of(1, 24)), Map.entry(1926, MonthDay.of(2, 13)),
        Map.entry(1927, MonthDay.of(2, 2)), Map.entry(1928, MonthDay.of(1, 23)), Map.entry(1929, MonthDay.of(2, 10)),
        Map.entry(1930, MonthDay.of(1, 30)), Map.entry(1931, MonthDay.of(2, 17)), Map.entry(1932, MonthDay.of(2, 6)),
        Map.entry(1933, MonthDay.of(1, 26)), Map.entry(1934, MonthDay.of(2, 14)), Map.entry(1935, MonthDay.of(2, 4)),
        Map.entry(1936, MonthDay.of(1, 24)), Map.entry(1937, MonthDay.of(2, 11)), Map.entry(1938, MonthDay.of(1, 31)),
        Map.entry(1939, MonthDay.of(2, 19)), Map.entry(1940, MonthDay.of(2, 8)), Map.entry(1941, MonthDay.of(1, 27)),
        Map.entry(1942, MonthDay.of(2, 15)), Map.entry(1943, MonthDay.of(2, 5)), Map.entry(1944, MonthDay.of(1, 25)),
        Map.entry(1945, MonthDay.of(2, 13)), Map.entry(1946, MonthDay.of(2, 2)), Map.entry(1947, MonthDay.of(1, 22)),
        Map.entry(1948, MonthDay.of(2, 10)), Map.entry(1949, MonthDay.of(1, 29)), Map.entry(1950, MonthDay.of(2, 17)),
        Map.entry(1951, MonthDay.of(2, 6)), Map.entry(1952, MonthDay.of(1, 27)), Map.entry(1953, MonthDay.of(2, 14)),
        Map.entry(1954, MonthDay.of(2, 3)), Map.entry(1955, MonthDay.of(1, 24)), Map.entry(1956, MonthDay.of(2, 12)),
        Map.entry(1957, MonthDay.of(1, 31)), Map.entry(1958, MonthDay.of(2, 18)), Map.entry(1959, MonthDay.of(2, 8)),
        Map.entry(1960, MonthDay.of(1, 28)), Map.entry(1961, MonthDay.of(2, 15)), Map.entry(1962, MonthDay.of(2, 5)),
        Map.entry(1963, MonthDay.of(1, 25)), Map.entry(1964, MonthDay.of(2, 13)), Map.entry(1965, MonthDay.of(2, 2)),
        Map.entry(1966, MonthDay.of(1, 21)), Map.entry(1967, MonthDay.of(2, 9)), Map.entry(1968, MonthDay.of(1, 30)),
        Map.entry(1969, MonthDay.of(2, 17)), Map.entry(1970, MonthDay.of(2, 6)), Map.entry(1971, MonthDay.of(1, 27)),
        Map.entry(1972, MonthDay.of(2, 15)), Map.entry(1973, MonthDay.of(2, 3)), Map.entry(1974, MonthDay.of(1, 23)),
        Map.entry(1975, MonthDay.of(2, 11)), Map.entry(1976, MonthDay.of(1, 31)), Map.entry(1977, MonthDay.of(2, 18)),
        Map.entry(1978, MonthDay.of(2, 7)), Map.entry(1979, MonthDay.of(1, 28)), Map.entry(1980, MonthDay.of(2, 16)),
        Map.entry(1981, MonthDay.of(2, 5)), Map.entry(1982, MonthDay.of(1, 25)), Map.entry(1983, MonthDay.of(2, 13)),
        Map.entry(1984, MonthDay.of(2, 2)), Map.entry(1985, MonthDay.of(2, 20)), Map.entry(1986, MonthDay.of(2, 9)),
        Map.entry(1987, MonthDay.of(1, 29)), Map.entry(1988, MonthDay.of(2, 17)), Map.entry(1989, MonthDay.of(2, 6)),
        Map.entry(1990, MonthDay.of(1, 27)), Map.entry(1991, MonthDay.of(2, 15)), Map.entry(1992, MonthDay.of(2, 4)),
        Map.entry(1993, MonthDay.of(1, 23)), Map.entry(1994, MonthDay.of(2, 10)), Map.entry(1995, MonthDay.of(1, 31)),
        Map.entry(1996, MonthDay.of(2, 19)), Map.entry(1997, MonthDay.of(2, 7)), Map.entry(1998, MonthDay.of(1, 28)),
        Map.entry(1999, MonthDay.of(2, 16)), Map.entry(2000, MonthDay.of(2, 5)), Map.entry(2001, MonthDay.of(1, 24)),
        Map.entry(2002, MonthDay.of(2, 12)), Map.entry(2003, MonthDay.of(2, 1)), Map.entry(2004, MonthDay.of(1, 22)),
        Map.entry(2005, MonthDay.of(2, 9)), Map.entry(2006, MonthDay.of(1, 29)), Map.entry(2007, MonthDay.of(2, 18)),
        Map.entry(2008, MonthDay.of(2, 7)), Map.entry(2009, MonthDay.of(1, 26)), Map.entry(2010, MonthDay.of(2, 14)),
        Map.entry(2011, MonthDay.of(2, 3)), Map.entry(2012, MonthDay.of(1, 23)), Map.entry(2013, MonthDay.of(2, 10)),
        Map.entry(2014, MonthDay.of(1, 31)), Map.entry(2015, MonthDay.of(2, 19)), Map.entry(2016, MonthDay.of(2, 8)),
        Map.entry(2017, MonthDay.of(1, 28)), Map.entry(2018, MonthDay.of(2, 16)), Map.entry(2019, MonthDay.of(2, 5)),
        Map.entry(2020, MonthDay.of(1, 25)), Map.entry(2021, MonthDay.of(2, 12)), Map.entry(2022, MonthDay.of(2, 1)),
        Map.entry(2023, MonthDay.of(1, 22)), Map.entry(2024, MonthDay.of(2, 10)), Map.entry(2025, MonthDay.of(1, 29)),
        Map.entry(2026, MonthDay.of(2, 17)), Map.entry(2027, MonthDay.of(2, 6)), Map.entry(2028, MonthDay.of(1, 26)),
        Map.entry(2029, MonthDay.of(2, 13)), Map.entry(2030, MonthDay.of(2, 3)), Map.entry(2031, MonthDay.of(1, 23)),
        Map.entry(2032, MonthDay.of(2, 11)), Map.entry(2033, MonthDay.of(1, 31)), Map.entry(2034, MonthDay.of(2, 19)),
        Map.entry(2035, MonthDay.of(2, 8)), Map.entry(2036, MonthDay.of(1, 28)), Map.entry(2037, MonthDay.of(2, 15)),
        Map.entry(2038, MonthDay.of(2, 4)), Map.entry(2039, MonthDay.of(1, 24)), Map.entry(2040, MonthDay.of(2, 12)),
        Map.entry(2041, MonthDay.of(2, 1)), Map.entry(2042, MonthDay.of(1, 22)), Map.entry(2043, MonthDay.of(2, 10)),
        Map.entry(2044, MonthDay.of(1, 30)), Map.entry(2045, MonthDay.of(2, 17)), Map.entry(2046, MonthDay.of(2, 6)),
        Map.entry(2047, MonthDay.of(1, 26)), Map.entry(2048, MonthDay.of(2, 14)), Map.entry(2049, MonthDay.of(2, 2)),
        Map.entry(2050, MonthDay.of(1, 23)), Map.entry(2051, MonthDay.of(2, 11)), Map.entry(2052, MonthDay.of(2, 1)),
        Map.entry(2053, MonthDay.of(2, 19)), Map.entry(2054, MonthDay.of(2, 8)), Map.entry(2055, MonthDay.of(1, 28)),
        Map.entry(2056, MonthDay.of(2, 15)), Map.entry(2057, MonthDay.of(2, 4)), Map.entry(2058, MonthDay.of(1, 24)),
        Map.entry(2059, MonthDay.of(2, 12)), Map.entry(2060, MonthDay.of(2, 2)), Map.entry(2061, MonthDay.of(1, 21)),
        Map.entry(2062, MonthDay.of(2, 9)), Map.entry(2063, MonthDay.of(1, 29)), Map.entry(2064, MonthDay.of(2, 17)),
        Map.entry(2065, MonthDay.of(2, 5)), Map.entry(2066, MonthDay.of(1, 26)), Map.entry(2067, MonthDay.of(2, 14)),
        Map.entry(2068, MonthDay.of(2, 3)), Map.entry(2069, MonthDay.of(1, 23)), Map.entry(2070, MonthDay.of(2, 11)),
        Map.entry(2071, MonthDay.of(1, 31)), Map.entry(2072, MonthDay.of(2, 19)), Map.entry(2073, MonthDay.of(2, 7)),
        Map.entry(2074, MonthDay.of(1, 27)), Map.entry(2075, MonthDay.of(2, 15)), Map.entry(2076, MonthDay.of(2, 5)),
        Map.entry(2077, MonthDay.of(1, 25)), Map.entry(2078, MonthDay.of(2, 12)), Map.entry(2079, MonthDay.of(2, 2)),
        Map.entry(2080, MonthDay.of(1, 22)), Map.entry(2081, MonthDay.of(2, 9)), Map.entry(2082, MonthDay.of(1, 29)),
        Map.entry(2083, MonthDay.of(2, 17)), Map.entry(2084, MonthDay.of(2, 6)), Map.entry(2085, MonthDay.of(1, 26)),
        Map.entry(2086, MonthDay.of(2, 14)), Map.entry(2087, MonthDay.of(2, 3)), Map.entry(2088, MonthDay.of(1, 24)),
        Map.entry(2089, MonthDay.of(2, 10)), Map.entry(2090, MonthDay.of(1, 30)), Map.entry(2091, MonthDay.of(2, 18)),
        Map.entry(2092, MonthDay.of(2, 7)), Map.entry(2093, MonthDay.of(1, 27)), Map.entry(2094, MonthDay.of(2, 15)),
        Map.entry(2095, MonthDay.of(2, 5)), Map.entry(2096, MonthDay.of(1, 25)), Map.entry(2097, MonthDay.of(2, 12)),
        Map.entry(2098, MonthDay.of(2, 1)), Map.entry(2099, MonthDay.of(1, 21)), Map.entry(2100, MonthDay.of(2, 9))
    );

    // ========== 十二生肖（地支顺序）+ 别称 + 五行 ==========
    private static final List<ZodiacInfo> ZODIACS = List.of(
        new ZodiacInfo("鼠", "子鼠", "水", "机智、灵活、适应力强", List.of("金色", "蓝色", "白色"), "北极星"),
        new ZodiacInfo("牛", "丑牛", "土", "稳重、踏实、勤奋耐劳", List.of("黄色", "橙色", "棕色"), "金星"),
        new ZodiacInfo("虎", "寅虎", "木", "勇敢、自信、领导力强", List.of("红色", "紫色", "金色"), "木星"),
        new ZodiacInfo("兔", "卯兔", "木", "温和、善良、细腻敏感", List.of("粉色", "绿色", "白色"), "木星"),
        new ZodiacInfo("龙", "辰龙", "土", "高贵、自信、志向远大", List.of("金色", "红色", "紫色"), "土星"),
        new ZodiacInfo("蛇", "巳蛇", "火", "智慧、冷静、洞察力强", List.of("红色", "黑色", "青色"), "火星"),
        new ZodiacInfo("马", "午马", "火", "奔放、热情、追求自由", List.of("红色", "橙色", "黄色"), "火星"),
        new ZodiacInfo("羊", "未羊", "土", "温柔、善良、富有艺术感", List.of("绿色", "白色", "粉色"), "土星"),
        new ZodiacInfo("猴", "申猴", "金", "聪明、活泼、反应敏捷", List.of("金色", "白色", "银色"), "金星"),
        new ZodiacInfo("鸡", "酉鸡", "金", "自律、果断、表现力强", List.of("金色", "黄色", "白色"), "金星"),
        new ZodiacInfo("狗", "戌狗", "土", "忠诚、正直、有责任感", List.of("棕色", "红色", "绿色"), "土星"),
        new ZodiacInfo("猪", "亥猪", "水", "善良、宽容、福气深厚", List.of("蓝色", "金色", "白色"), "水星")
    );

    // ========== 十二星座（占星学标准日期边界） ==========
    private static final List<ConstellationInfo> CONSTELLATIONS = List.of(
        new ConstellationInfo("摩羯座", "Capricorn", "土象", "土星", MonthDay.of(12, 22), MonthDay.of(1, 19),
            "务实、稳重、有野心、自律", List.of("深棕色", "黑色", "藏青色"), 10),
        new ConstellationInfo("水瓶座", "Aquarius", "风象", "天王星", MonthDay.of(1, 20), MonthDay.of(2, 18),
            "独立、创新、理性、友善", List.of("蓝色", "青色", "银色"), 11),
        new ConstellationInfo("双鱼座", "Pisces", "水象", "海王星", MonthDay.of(2, 19), MonthDay.of(3, 20),
            "浪漫、感性、富有想象力、善良", List.of("淡紫色", "海蓝色", "粉色"), 12),
        new ConstellationInfo("白羊座", "Aries", "火象", "火星", MonthDay.of(3, 21), MonthDay.of(4, 19),
            "热情、勇敢、直率、行动力强", List.of("红色", "橙色", "金色"), 1),
        new ConstellationInfo("金牛座", "Taurus", "土象", "金星", MonthDay.of(4, 20), MonthDay.of(5, 20),
            "踏实、稳重、追求品质、务实", List.of("绿色", "粉色", "棕色"), 2),
        new ConstellationInfo("双子座", "Gemini", "风象", "水星", MonthDay.of(5, 21), MonthDay.of(6, 21),
            "聪明、多变、好奇心强、沟通力好", List.of("黄色", "橙色", "浅蓝"), 3),
        new ConstellationInfo("巨蟹座", "Cancer", "水象", "月亮", MonthDay.of(6, 22), MonthDay.of(7, 22),
            "温柔、体贴、重视家庭、敏感", List.of("银色", "白色", "淡绿色"), 4),
        new ConstellationInfo("狮子座", "Leo", "火象", "太阳", MonthDay.of(7, 23), MonthDay.of(8, 22),
            "自信、大方、领导力强、热情", List.of("金色", "橙色", "红色"), 5),
        new ConstellationInfo("处女座", "Virgo", "土象", "水星", MonthDay.of(8, 23), MonthDay.of(9, 22),
            "细致、严谨、追求完美、有条理", List.of("灰色", "米色", "淡紫色"), 6),
        new ConstellationInfo("天秤座", "Libra", "风象", "金星", MonthDay.of(9, 23), MonthDay.of(10, 23),
            "优雅、公正、追求平衡、社交力强", List.of("粉色", "淡蓝色", "白色"), 7),
        new ConstellationInfo("天蝎座", "Scorpio", "水象", "冥王星", MonthDay.of(10, 24), MonthDay.of(11, 22),
            "神秘、坚毅、洞察力强、深情", List.of("深红色", "黑色", "酒红色"), 8),
        new ConstellationInfo("射手座", "Sagittarius", "火象", "木星", MonthDay.of(11, 23), MonthDay.of(12, 21),
            "乐观、自由、热爱冒险、正直", List.of("紫色", "深蓝色", "橙色"), 9)
    );

    private final ObjectMapper mapper;

    public CalculateZodiacInfoTool() {
        this(new ObjectMapper());
    }

    public CalculateZodiacInfoTool(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    // ============================================================
    // FunctionTool 接口
    // ============================================================

    @Override
    public String name() {
        return "calculate_zodiac_info";
    }

    @Override
    public JsonNode definition() {
        ObjectNode function = mapper.createObjectNode();
        function.put("name", name());
        function.put("description",
            "【必须优先调用 · 纯本地精确计算】根据用户给出的公历出生日期（年月日），一次性精确计算出 4 个核心信息："
            + "生肖（农历算法，以春节为界）、十二星座（占星学精确日期范围）、周岁（国际实岁，生日未过减 1）、虚岁（中国传统算法，出生算 1 岁，过春节加 1 岁），"
            + "同时附带本命年判断、下次生日倒计时、生肖五行/性格、星座元素/守护星/幸运色/性格关键词等附加信息。"
            + "触发关键词（命中任意一个必须调用本工具，禁止凭记忆/常识瞎算）：生肖、属相、属什么、星座、周岁、虚岁、几岁了、本命年、生日计算、出生年月日、农历属相、"
            + "「我属什么」「他是什么星座」「今年是本命年吗」「我今年虚岁多少」「满几周岁了」「距离生日还有多少天」这类和出生年月日生肖星座年龄相关的问题。"
            + "【强规则 1·零容忍】：生肖绝对不能按公历 1 月 1 日分界，必须按农历春节（正月初一）分界！比如 2025 年 1 月 29 日（春节）之前出生的仍然属龙，1 月 29 日及之后出生才属蛇，这一类分界日附近的日期绝对不能算错！"
            + "【强规则 2·零容忍】：虚岁绝对不能直接「周岁 + 1」，必须用传统算法（出生即 1 岁，每过一个农历春节加 1 岁，生日没过但春节过了也要加）！"
            + "【强规则 3】：工具调用后返回 JSON 里的 `reply` 字段是最终回复用户的完整文字模板，你最终输出给用户的全部文字必须 100% 等于本次返回的 reply 原文，一个字一个标点数字 emoji 都不能改，不能用自己的话总结，不能改数字。"
            + "【强规则 4】：本工具纯本地计算，1 次调用就能拿到全部信息，调用完本工具后绝对禁止再调 web_search 或任何其他工具去重新查生肖/星座/年龄！调用失败就把错误告诉用户，不许 fallback。");

        ObjectNode parameters = function.putObject("parameters");
        parameters.put("type", "object");
        ObjectNode props = parameters.putObject("properties");
        props.putObject("birth_date")
            .put("type", "string")
            .put("description",
                "【必填】用户的公历出生日期，必须严格使用 yyyy-MM-dd 格式，例如 1998-05-20（1998 年 5 月 20 日）、2010-02-14。"
                + "如果用户说「98 年 5 月 20」「2000 年春节前一天」「我 25 岁属龙」这种模糊日期，必须先把它转成精确的 yyyy-MM-dd 再传入，禁止传空、禁止传「00/00/00」这种非法格式。"
                + "年份范围 1900 到 2100 之间即可。如果用户一次说了多个人的生日，只取其中第一个完整的日期传入即可。");
        ObjectNode genderNode = props.putObject("gender");
        genderNode.put("type", "string");
        ArrayNode genderEnum = genderNode.putArray("enum");
        genderEnum.add("男").add("女").add("未知");
        genderNode.put("description", "【选填】用户性别（男/女/未知），不影响核心计算，仅用于个性化文案措辞。如果没说就传「未知」。");
        parameters.putArray("required").add("birth_date");

        ObjectNode tool = mapper.createObjectNode();
        tool.put("type", "function");
        tool.set("function", function);
        return tool;
    }

    @Override
    public String execute(JsonNode arguments) throws Exception {
        if (arguments == null || !arguments.isObject()) {
            return errResp("工具参数为空或不是合法 JSON 对象，请提供 birth_date 参数");
        }

        String birthStr = arguments.path("birth_date").asText("").trim();
        String gender = arguments.path("gender").asText("未知").trim();
        if (gender.isBlank()) gender = "未知";
        if (!List.of("男", "女", "未知").contains(gender)) gender = "未知";

        if (birthStr.isEmpty()) {
            return errResp("birth_date 参数不能为空，请提供 yyyy-MM-dd 格式的公历出生日期");
        }

        LocalDate birth;
        try {
            birth = LocalDate.parse(birthStr, DATE_FMT);
        } catch (DateTimeParseException e) {
            return errResp("birth_date 格式不正确：" + birthStr + "，必须是 yyyy-MM-dd 格式（例如 1998-05-20）");
        }
        LocalDate today = LocalDate.now(DEFAULT_TZ);
        if (birth.isAfter(today)) {
            return errResp("出生日期「" + DATE_FMT_CN.format(birth) + "」晚于今天，不能计算，请核对日期是否正确");
        }
        if (birth.getYear() < 1900 || birth.getYear() > 2100) {
            return errResp("目前仅支持 1900~2100 年之间的日期，您输入的年份是 " + birth.getYear() + "，超出范围");
        }

        // ===== 1. 生肖 =====
        ZodiacInfo zodiac = calcZodiac(birth);
        String lunarYearDesc = describeLunarYearBoundary(birth);

        // ===== 2. 星座 =====
        ConstellationInfo constellation = calcConstellation(MonthDay.from(birth));

        // ===== 3. 周岁 =====
        int fullAge = calcFullAge(birth, today);
        boolean birthdayPassedThisYear = isBirthdayPassed(birth, today);

        // ===== 4. 虚岁（传统算法） =====
        int nominalAge = calcNominalAge(birth, today);

        // ===== 5. 本命年 & 下次生日倒计时 =====
        int currentYearZodiacIdx = todayZodiacIndex(today);
        boolean isThisYearBenMing = currentYearZodiacIdx == zodiac.index;
        LocalDate nextSpringFestival = nextSpringFestivalDate(today);
        boolean nextYearIsBenMing = false;
        if (nextSpringFestival != null) {
            int nextSpringY = nextSpringFestival.getYear();
            Integer nextIdx = ZODIACS.indexOf(findZodiacByYear(nextSpringY));
            if (nextIdx != null && nextIdx == zodiac.index) nextYearIsBenMing = true;
            if (!isThisYearBenMing && today.getMonthValue() < 12) {
                // 春节之后的年份判断
            }
        }
        long daysToNextBirthday = calcDaysToNextBirthday(birth, today);
        LocalDate nextBirthdayDate = nextBirthday(birth, today);

        // ===== 6. 构造 reply 文案（数字全部精确计算，LLM 直接原样输出即可） =====
        String genderHonorific = gender.equals("男") ? "同学" : gender.equals("女") ? "同学" : "朋友";
        StringBuilder replySb = new StringBuilder();
        replySb.append("🎂 查完啦！根据您提供的公历生日 **").append(DATE_FMT_CN.format(birth)).append("**，为您计算好全部信息啦 ✨\n\n");
        replySb.append("🐲 **生肖（按农历春节分界）**：**").append(zodiac.nameWithGanZhi).append("**\n");
        replySb.append("   · 生肖五行：**").append(zodiac.element).append("**\n");
        replySb.append("   · 性格关键词：").append(zodiac.traits).append("\n");
        replySb.append("   · 幸运色：").append(String.join("、", zodiac.luckyColors)).append("\n");
        replySb.append("   · ").append(lunarYearDesc).append("\n\n");
        replySb.append("⭐ **星座**：**").append(constellation.cnName).append(" ").append(constellation.enName).append("**\n");
        replySb.append("   · 元素属性：**").append(constellation.element).append("**｜守护星：**").append(constellation.rulingPlanet).append("**\n");
        replySb.append("   · 性格关键词：").append(constellation.traits).append("\n");
        replySb.append("   · 幸运色：").append(String.join("、", constellation.luckyColors)).append("\n");
        replySb.append("   · 日期范围：").append(formatMD(constellation.start)).append(" ~ ").append(formatMD(constellation.end)).append("\n\n");
        replySb.append("🎈 **年龄**：\n");
        replySb.append("   · 周岁（实岁）：**").append(fullAge).append(" 岁**").append(birthdayPassedThisYear ? "（今年生日已过🎉）" : "（今年生日还没到哦）").append("\n");
        replySb.append("   · 虚岁（传统算法）：**").append(nominalAge).append(" 岁**（出生即 1 岁，每过农历春节 +1）\n\n");
        replySb.append("⏰ **倒计时 & 本命年**：\n");
        replySb.append("   · 距离下次生日（").append(DATE_FMT_CN.format(nextBirthdayDate)).append("）还有 **").append(daysToNextBirthday).append(" 天** 🎁\n");
        if (isThisYearBenMing) {
            replySb.append("   · 🔴 **今年是本命年！** 红红火火顺顺利利，建议穿红色哦 🧧\n");
        } else if (nextYearIsBenMing) {
            replySb.append("   · 🟠 **明年就是本命年啦**（").append(nextSpringFestival.getYear()).append(" 年春节后进入本命年）\n");
        } else {
            replySb.append("   · 今年/明年暂未到本命年，平常心就好～\n");
        }
        replySb.append("\n💡 小贴士：本工具纯本地精确计算，不会上传任何生日信息，放心使用哦～");

        String reply = replySb.toString();

        // ===== 7. 内部结构化 proof（Base64 藏起来，LLM 看不到独立字段避免幻觉改数字） =====
        ObjectNode proof = mapper.createObjectNode();
        proof.put("birth_date_yyyy_MM_dd", DATE_FMT.format(birth));
        proof.put("gender", gender);
        proof.put("zodiac_cn", zodiac.name);
        proof.put("zodiac_full", zodiac.nameWithGanZhi);
        proof.put("zodiac_element", zodiac.element);
        proof.put("zodiac_index", zodiac.index);
        proof.put("constellation_cn", constellation.cnName);
        proof.put("constellation_en", constellation.enName);
        proof.put("constellation_element", constellation.element);
        proof.put("constellation_order", constellation.astrologyOrder);
        proof.put("age_full", fullAge);
        proof.put("age_nominal_traditional", nominalAge);
        proof.put("birthday_passed_this_year", birthdayPassedThisYear);
        proof.put("days_to_next_birthday", daysToNextBirthday);
        proof.put("next_birthday_date", DATE_FMT.format(nextBirthdayDate));
        proof.put("is_this_year_ben_ming_nian", isThisYearBenMing);
        proof.put("is_next_year_ben_ming_nian", nextYearIsBenMing);
        proof.put("calculation_note", "生肖以农历春节为界，周岁按生日未到减一，虚岁采用传统算法：出生即1岁每过春节+1");

        ObjectNode out = mapper.createObjectNode();
        out.put("success", true);
        out.put("reply", reply);
        ArrayNode zodiacArray = out.putArray("zodiac");
        zodiacArray.add(zodiac.name);
        ArrayNode constellationArray = out.putArray("constellation");
        constellationArray.add(constellation.cnName);
        out.put("age_full", fullAge);
        out.put("age_nominal", nominalAge);
        out.put("days_to_next_birthday", daysToNextBirthday);
        out.put("is_ben_ming_nian", isThisYearBenMing);
        out.put("_internal_proof", Base64.getEncoder().encodeToString(mapper.writeValueAsString(proof).getBytes(StandardCharsets.UTF_8)));
        return mapper.writeValueAsString(out);
    }

    // ============================================================
    // 辅助方法：生肖
    // ============================================================

    private ZodiacInfo calcZodiac(LocalDate birth) {
        int year = birth.getYear();
        MonthDay springFestival = SPRING_FESTIVAL_TABLE.get(year);
        if (springFestival == null) {
            int idx = (year - 4) % 12;
            if (idx < 0) idx += 12;
            ZodiacInfo z = ZODIACS.get(idx);
            return new ZodiacInfo(z, idx);
        }
        boolean beforeSpringFestival = MonthDay.from(birth).isBefore(springFestival);
        int zodiacYear = beforeSpringFestival ? year - 1 : year;
        int idx = (zodiacYear - 4) % 12;
        if (idx < 0) idx += 12;
        ZodiacInfo z = ZODIACS.get(idx);
        return new ZodiacInfo(z, idx);
    }

    private ZodiacInfo findZodiacByYear(int year) {
        int idx = (year - 4) % 12;
        if (idx < 0) idx += 12;
        ZodiacInfo z = ZODIACS.get(idx);
        return new ZodiacInfo(z, idx);
    }

    private int todayZodiacIndex(LocalDate today) {
        int year = today.getYear();
        MonthDay spring = SPRING_FESTIVAL_TABLE.get(year);
        int zodiacYear;
        if (spring != null && MonthDay.from(today).isBefore(spring)) {
            zodiacYear = year - 1;
        } else {
            zodiacYear = year;
        }
        int idx = (zodiacYear - 4) % 12;
        return idx < 0 ? idx + 12 : idx;
    }

    private LocalDate nextSpringFestivalDate(LocalDate today) {
        for (int y = today.getYear(); y <= today.getYear() + 2; y++) {
            MonthDay md = SPRING_FESTIVAL_TABLE.get(y);
            if (md == null) continue;
            LocalDate sf = md.atYear(y);
            if (!sf.isBefore(today)) return sf;
        }
        return null;
    }

    private String describeLunarYearBoundary(LocalDate birth) {
        int y = birth.getYear();
        MonthDay sf = SPRING_FESTIVAL_TABLE.get(y);
        if (sf == null) return "生肖按农历年计算，分界日为春节";
        boolean before = MonthDay.from(birth).isBefore(sf);
        LocalDate sfDate = sf.atYear(y);
        if (before) {
            ZodiacInfo pre = findZodiacByYear(y - 1);
            ZodiacInfo cur = findZodiacByYear(y);
            return "说明：您生日在当年春节（" + DATE_FMT_CN.format(sfDate) + "）之前，所以属相是 " + pre.name + "（不是公历年份 " + y + " 对应的" + cur.name + "，这是最容易搞错的地方哦⚠️）";
        } else {
            ZodiacInfo cur = findZodiacByYear(y);
            return "说明：您生日在当年春节（" + DATE_FMT_CN.format(sfDate) + "）及之后，属相为 " + cur.name + " ✅";
        }
    }

    // ============================================================
    // 辅助方法：星座
    // ============================================================

    private ConstellationInfo calcConstellation(MonthDay md) {
        for (ConstellationInfo c : CONSTELLATIONS) {
            if (isInRange(md, c.start, c.end)) return c;
        }
        return CONSTELLATIONS.get(0);
    }

    private static boolean isInRange(MonthDay target, MonthDay start, MonthDay end) {
        if (!start.isAfter(end)) {
            return !target.isBefore(start) && !target.isAfter(end);
        } else {
            // 跨年度的摩羯座：12.22 ~ 次年 1.19
            return !target.isBefore(start) || !target.isAfter(end);
        }
    }

    private static String formatMD(MonthDay md) {
        return md.getMonthValue() + "月" + md.getDayOfMonth() + "日";
    }

    // ============================================================
    // 辅助方法：周岁 / 虚岁 / 生日倒计时
    // ============================================================

    private static int calcFullAge(LocalDate birth, LocalDate today) {
        int age = today.getYear() - birth.getYear();
        if (!isBirthdayPassed(birth, today)) age--;
        return Math.max(0, age);
    }

    private static boolean isBirthdayPassed(LocalDate birth, LocalDate today) {
        MonthDay birthday = MonthDay.from(birth);
        MonthDay todayMD = MonthDay.from(today);
        return !todayMD.isBefore(birthday);
    }

    private static int calcNominalAge(LocalDate birth, LocalDate today) {
        int countSpringFestivals = 0;
        // 从出生那一年到今年，数一下过了多少个农历春节
        LocalDate firstSF = firstSpringFestivalOnOrAfter(birth);
        LocalDate cursor = firstSF;
        while (cursor != null && !cursor.isAfter(today)) {
            countSpringFestivals++;
            cursor = nextSpringFestivalAfter(cursor);
        }
        // 传统算法：出生即 1 岁 + 过了几个春节
        return 1 + countSpringFestivals;
    }

    private static LocalDate firstSpringFestivalOnOrAfter(LocalDate birth) {
        for (int y = birth.getYear(); y <= birth.getYear() + 1; y++) {
            MonthDay md = SPRING_FESTIVAL_TABLE.get(y);
            if (md == null) continue;
            LocalDate sf = md.atYear(y);
            if (!sf.isBefore(birth)) return sf;
        }
        return null;
    }

    private static LocalDate nextSpringFestivalAfter(LocalDate date) {
        int y = date.getYear();
        MonthDay thisYear = SPRING_FESTIVAL_TABLE.get(y);
        if (thisYear != null) {
            LocalDate sf = thisYear.atYear(y);
            if (sf.isAfter(date)) return sf;
        }
        MonthDay nextYear = SPRING_FESTIVAL_TABLE.get(y + 1);
        if (nextYear != null) return nextYear.atYear(y + 1);
        return null;
    }

    private static LocalDate nextBirthday(LocalDate birth, LocalDate today) {
        MonthDay bd = MonthDay.from(birth);
        LocalDate thisYearBd = bd.atYear(today.getYear());
        if (!thisYearBd.isBefore(today)) return thisYearBd;
        return bd.atYear(today.getYear() + 1);
    }

    private static long calcDaysToNextBirthday(LocalDate birth, LocalDate today) {
        LocalDate nextBd = nextBirthday(birth, today);
        return ChronoUnit.DAYS.between(today, nextBd);
    }

    // ============================================================
    // 数据结构 & 错误响应
    // ============================================================

    private record ZodiacInfo(
        String name, String nameWithGanZhi, String element, String traits,
        List<String> luckyColors, String rulingPlanet, int index
    ) {
        ZodiacInfo(String n, String n2, String e, String t, List<String> c, String p) {
            this(n, n2, e, t, c, p, -1);
        }
        ZodiacInfo(ZodiacInfo z, int idx) {
            this(z.name, z.nameWithGanZhi, z.element, z.traits, z.luckyColors, z.rulingPlanet, idx);
        }
    }

    private record ConstellationInfo(
        String cnName, String enName, String element, String rulingPlanet,
        MonthDay start, MonthDay end, String traits, List<String> luckyColors, int astrologyOrder
    ) {}

    private String errResp(String message) throws Exception {
        ObjectNode proof = mapper.createObjectNode();
        proof.put("error", message);
        ObjectNode out = mapper.createObjectNode();
        out.put("success", false);
        out.put("reply", "不好意思，生日信息算不出来哦😅 原因：" + message
            + "。请确认一下公历出生日期是否正确（格式 yyyy-MM-dd，例如 1998-05-20），再试一下就好啦～");
        out.put("_internal_proof", Base64.getEncoder().encodeToString(mapper.writeValueAsString(proof).getBytes(StandardCharsets.UTF_8)));
        return mapper.writeValueAsString(out);
    }
}
