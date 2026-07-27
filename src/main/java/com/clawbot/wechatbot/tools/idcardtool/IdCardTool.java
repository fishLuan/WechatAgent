package com.clawbot.wechatbot.tools.idcardtool;

import com.clawbot.wechatbot.tools.FunctionTool;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.Map;

/**
 * 中国大陆18位居民身份证号码校验工具。
 *
 * 基于 GB 11643-1999 标准实现，纯本地计算：
 *   1. 格式校验 —— 18位，前17位数字，末位数字或大写X
 *   2. 校验位验证 —— ISO 7064:1983 MOD 11-2
 *   3. 地区解析 —— 前6位行政区划码 → 省/直辖市/自治区
 *   4. 出生日期解析 —— 第7-14位 → 年月日 + 年龄
 *   5. 性别判断 —— 第17位奇偶
 *
 * 身份证号不会离开进程，不会调用任何外部 API。
 */
public class IdCardTool implements FunctionTool {

    // ---- 校验位常量 (ISO 7064:1983 MOD 11-2) ----
    private static final int[] WEIGHTS = {7, 9, 10, 5, 8, 4, 2, 1, 6, 3, 7, 9, 10, 5, 8, 4, 2};
    private static final char[] CHECK_CODES = {'1', '0', 'X', '9', '8', '7', '6', '5', '4', '3', '2'};

    // ---- 省级行政区划码（前2位）----
    private static final Map<String, String> PROVINCE_MAP = Map.ofEntries(
        Map.entry("11", "北京市"), Map.entry("12", "天津市"), Map.entry("13", "河北省"),
        Map.entry("14", "山西省"), Map.entry("15", "内蒙古自治区"),
        Map.entry("21", "辽宁省"), Map.entry("22", "吉林省"), Map.entry("23", "黑龙江省"),
        Map.entry("31", "上海市"), Map.entry("32", "江苏省"), Map.entry("33", "浙江省"),
        Map.entry("34", "安徽省"), Map.entry("35", "福建省"), Map.entry("36", "江西省"),
        Map.entry("37", "山东省"),
        Map.entry("41", "河南省"), Map.entry("42", "湖北省"), Map.entry("43", "湖南省"),
        Map.entry("44", "广东省"), Map.entry("45", "广西壮族自治区"), Map.entry("46", "海南省"),
        Map.entry("50", "重庆市"), Map.entry("51", "四川省"), Map.entry("52", "贵州省"),
        Map.entry("53", "云南省"), Map.entry("54", "西藏自治区"),
        Map.entry("61", "陕西省"), Map.entry("62", "甘肃省"), Map.entry("63", "青海省"),
        Map.entry("64", "宁夏回族自治区"), Map.entry("65", "新疆维吾尔自治区"),
        Map.entry("71", "台湾省"), Map.entry("81", "香港特别行政区"), Map.entry("82", "澳门特别行政区")
    );

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final ObjectMapper mapper;

    public IdCardTool() {
        this(new ObjectMapper());
    }

    public IdCardTool(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    // ============================================================
    // FunctionTool 接口
    // ============================================================

    @Override
    public String name() {
        return "validate_id_card";
    }

    @Override
    public JsonNode definition() {
        ObjectNode function = mapper.createObjectNode();
        function.put("name", name());
        function.put("description",
            "【最严重级别 · 违规零容忍】中国大陆18位居民身份证校验专用工具。"
            + "只要用户消息里出现 18 位号码，或者有「验证身份证」「校验身份证」「查身份证真假」「解析身份证地区/生日/性别」"
            + "等任何与身份证相关的意图，你必须在生成任何回复文字之前 **首先调用 validate_id_card 工具一次，且只能调用本工具，不允许不调用就回答**。"
            + "（⚠️ 大模型自己计算身份证校验位的错误率极高，凭记忆/训练数据/训练印象计算的结果完全不可信，校验位、生日、性别、地区全部必须交给本工具精确计算。）"
            + "【致命反幻觉硬规则 1】绝对不允许参考或复用对话历史（包括用户上一条消息、你上一条回复、之前任意轮次对话、历史例子）里出现过的任何"
            + "身份证校验位数字或『正确末位应为 X』『正确末位应为 5』这类断言，每一次用户发来新的身份证号，都必须当作一个全新的、和历史完全无关的问题，"
            + "必须重新调用 validate_id_card 工具重新计算，绝对不能「套用上一次算过的结果」。"
            + "【致命反幻觉硬规则 2】本 description 里没有任何「正确末位是某个具体数字」的例子，也不会写任何具体的校验位值（0~9、X 都不会写具体示例），"
            + "请不要凭空想象、不要根据经验猜、不要凭感觉写，校验位正确值 100% 只能来自本工具本次调用返回 JSON 里的 reply 字段。"
            + "【调用后必须严格遵守】工具调用成功返回后，你最终回复给用户的全部文字内容，必须 100% 等于本次（注意是本次，不是历史任何一次）返回 JSON 顶层 `reply` 字段的原文："
            + "一个字、一个标点、一个数字、一个 emoji、一个 Markdown 标记、一个换行都不能改，不能用自己的话总结，不能替换数字，不能改大小写，不能改粗体/反引号标记，"
            + "不能把 reply 里写的粗体数字改成别的数字或 X，也不能把别的数字替换成 X。"
            + "返回 JSON 里除 `reply` 之外的其他字段（例如 `_internal_proof`、`valid`）全部是给系统内部使用的，你不需要读，也不要参考或复述，只看 `reply`。"
            + "【回答前 Checklist 强制自检】在把文字发给用户前，先在心里逐项打勾：(1) 我是不是调用了 validate_id_card？→ 没调就不许回答，先去调用。"
            + "(2) 我要输出的内容是不是和本次工具返回的 reply 原文逐字完全一致？→ 有任何不同都必须改回 reply 原文。(3) 我要写的校验位数字，是不是完全来自本次 reply 里的原文，而不是我自己想的、或历史里看到的？→ 不是就必须用 reply 里的。"
            + "调用工具后不要追问用户任何问题，直接输出本次 reply 原文即可结束对话轮次。");

        ObjectNode parameters = function.putObject("parameters");
        parameters.put("type", "object");
        ObjectNode properties = parameters.putObject("properties");
        properties.putObject("id_number")
            .put("type", "string")
            .put("description",
                "【必填】用户发来的 18 位身份证号，必须原样、完整、一个字符都不改地传入。"
                + "不要裁剪、不要去空格、不要去中划线、不要改大小写（X 也不要改成小写），用户写什么就传什么。"
                + "如果用户一次发了多个号，也只选其中第一个 18 位号原样传入即可，其他的不要改、不要合并。");
        parameters.putArray("required").add("id_number");

        ObjectNode tool = mapper.createObjectNode();
        tool.put("type", "function");
        tool.set("function", function);
        return tool;
    }

    @Override
    public String execute(JsonNode arguments) throws Exception {
        if (arguments == null || !arguments.isObject()) {
            return errResp("工具参数必须是 JSON 对象");
        }

        String id = arguments.path("id_number").asText("").trim().toUpperCase();
        if (id.isEmpty()) {
            return errResp("id_number 参数不能为空");
        }

        // ---- 1. 格式校验 ----
        if (!id.matches("\\d{17}[\\dX]")) {
            return errResp("身份证号格式不正确：必须是18位，前17位为数字，末位为数字或大写X");
        }

        // ---- 2. 校验位验证 (ISO 7064:1983 MOD 11-2) ----
        int sum = 0;
        for (int i = 0; i < 17; i++) {
            sum += (id.charAt(i) - '0') * WEIGHTS[i];
        }
        int remainder = sum % 11;
        char expectedCheck = CHECK_CODES[remainder];
        char actualLast = id.charAt(17);

        if (actualLast != expectedCheck) {
            String first17 = id.substring(0, 17);
            String correctedId = first17 + expectedCheck;
            String reply = "查完啦！这个身份证号**无效**❌\n"
                + "原因：**校验位不匹配**（正确末位第18位应为 **" + expectedCheck
                + "**，实际末位是 **" + actualLast + "**）。\n"
                + "如果前17位数字都没错，那修正后的完整号码应该是：`" + correctedId
                + "`。可能是某位数字打错了，仔细核对一下吧～🔍😄";
            ObjectNode proof = mapper.createObjectNode();
            proof.put("actual_check_digit", String.valueOf(actualLast));
            proof.put("expected_check_digit", String.valueOf(expectedCheck));
            proof.put("suggested_correct_id_number", correctedId);
            proof.put("remainder", remainder);
            proof.put("weighted_sum", sum);
            String proofStr = mapper.writeValueAsString(proof);
            ObjectNode out = mapper.createObjectNode();
            out.put("valid", false);
            out.put("reply", reply);
            out.put("_internal_proof", Base64.getEncoder().encodeToString(proofStr.getBytes(StandardCharsets.UTF_8)));
            return mapper.writeValueAsString(out);
        }

        // ---- 3. 地区解析 ----
        String areaCode = id.substring(0, 6);
        String provinceCode = id.substring(0, 2);
        String province = PROVINCE_MAP.getOrDefault(provinceCode, "未知地区");

        // ---- 4. 出生日期解析 ----
        String birthStr = id.substring(6, 14);
        LocalDate birthDate;
        try {
            birthDate = LocalDate.parse(birthStr, DATE_FMT);
        } catch (DateTimeParseException e) {
            return errResp("出生日期 " + formatBirthDisplay(birthStr) + " 不是合法的日期");
        }
        if (birthDate.isAfter(LocalDate.now())) {
            return errResp("出生日期 " + formatBirthDisplay(birthStr) + " 晚于当前日期，不合法");
        }

        // ---- 5. 年龄 + 性别 ----
        int age = calcAge(birthDate);
        int genderDigit = id.charAt(16) - '0';
        String gender = (genderDigit % 2 == 1) ? "男" : "女";
        String genderDesc = (genderDigit % 2 == 1) ? "奇数" : "偶数";

        // ---- 6. 成功结果（reply 写死所有数字，LLM 只需要直接输出这一段）----
        String reply = "查完啦！这个身份证号**有效**✅\n"
            + "🗺️ 签发地区：**" + province + "**（行政区划码 " + areaCode + "）\n"
            + "🎂 出生日期：**" + formatBirthDisplay(birthStr) + "**（今年 " + age + " 岁）\n"
            + "⚧️ 性别：**" + gender + "**（第17位为 " + genderDigit + "，" + genderDesc + "）\n"
            + "🔐 校验位：末位 **" + actualLast + "** 匹配通过\n"
            + "⚠️ 温馨提示：身份证号是敏感个人信息，不要随意发给陌生人哦，"
            + "本校验纯本地计算，不会存储或上传任何数据～";

        ObjectNode proof = mapper.createObjectNode();
        proof.put("id_number", id);
        proof.put("area_code", areaCode);
        proof.put("province", province);
        proof.put("birth_date", formatBirthDisplay(birthStr));
        proof.put("age", age);
        proof.put("gender", gender);
        proof.put("check_digit", String.valueOf(actualLast));
        proof.put("privacy_notice",
            "身份证号是敏感个人信息，请勿随意发送给陌生人或机器人。本校验纯本地计算，不会存储或上传任何数据。");
        String proofStr = mapper.writeValueAsString(proof);

        ObjectNode out = mapper.createObjectNode();
        out.put("valid", true);
        out.put("reply", reply);
        out.put("_internal_proof", Base64.getEncoder().encodeToString(proofStr.getBytes(StandardCharsets.UTF_8)));
        return mapper.writeValueAsString(out);
    }

    // ============================================================
    // 辅助方法
    // ============================================================

    /** 把 yyyyMMdd 格式化成 yyyy-MM-dd 用于展示。 */
    private static String formatBirthDisplay(String raw) {
        return raw.substring(0, 4) + "-" + raw.substring(4, 6) + "-" + raw.substring(6, 8);
    }

    /** 计算周岁（到当前日期）。 */
    private static int calcAge(LocalDate birth) {
        LocalDate today = LocalDate.now();
        int age = today.getYear() - birth.getYear();
        if (today.getMonthValue() < birth.getMonthValue()
            || (today.getMonthValue() == birth.getMonthValue()
                && today.getDayOfMonth() < birth.getDayOfMonth())) {
            age--;
        }
        return age;
    }

    /** 错误分支的统一响应（数字藏在 Base64 proof 里，LLM 看不到独立数字字段）。 */
    private String errResp(String message) throws Exception {
        ObjectNode proof = mapper.createObjectNode();
        proof.put("error", message);
        String proofStr = mapper.writeValueAsString(proof);
        ObjectNode out = mapper.createObjectNode();
        out.put("valid", false);
        out.put("reply", "不好意思，这个身份证号没法校验哦😅 原因：" + message
            + "。请再检查一下输入的内容是否正确～");
        out.put("_internal_proof", Base64.getEncoder().encodeToString(proofStr.getBytes(StandardCharsets.UTF_8)));
        return mapper.writeValueAsString(out);
    }
}