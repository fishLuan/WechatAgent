package com.Student.wechatbot.util;

/**
 * JSON 工具类：把 SimpleBot 里散落的 JSON 处理方法集中在这里
 */
public final class JsonUtils {

    private JsonUtils() {}

    /**
     * 把普通字符串转成 JSON 字符串字面量（加引号+转义特殊字符）
     * "你好\"世界" → "\"你好\\\"世界\""
     */
    public static String escape(String s) {
        if (s == null) return "\"\"";
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':  sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        sb.append("\"");
        return sb.toString();
    }

    /**
     * 从 JSON 中提取 "content" 字段的值（DeepSeek/百炼响应的通用结构）
     * 支持两种：
     *   1) "content": "文本"
     *   2) "content": [{"text": "文本"}, ...] （数组形式）
     */
    public static String extractContent(String json) {
        // 先尝试数组形式："content": [{"text": "xxx"}]
        int arrStart = json.indexOf("\"content\":");
        if (arrStart >= 0) {
            int bracket = json.indexOf("[", arrStart);
            if (bracket > arrStart && bracket - arrStart < 50) {
                // 看起来是数组，找 "text":
                int textIdx = json.indexOf("\"text\"", bracket);
                if (textIdx > 0) {
                    int colon = json.indexOf(":", textIdx);
                    int q1 = json.indexOf("\"", colon + 1);
                    if (q1 > 0) {
                        return extractQuotedString(json, q1);
                    }
                }
            }
        }

        // 再尝试普通字符串形式："content": "文本"
        int idx = json.lastIndexOf("\"content\"");
        if (idx < 0) return null;
        int colon = json.indexOf(":", idx);
        if (colon < 0) return null;
        int q1 = json.indexOf("\"", colon + 1);
        if (q1 < 0) return null;
        return extractQuotedString(json, q1);
    }

    /**
     * 从 json 的 q1 位置（一个 " 字符）开始，读取到配对的 " 为止，处理转义
     */
    private static String extractQuotedString(String json, int q1) {
        int q2 = q1 + 1;
        StringBuilder sb = new StringBuilder();
        boolean escape = false;
        while (q2 < json.length()) {
            char c = json.charAt(q2);
            if (escape) {
                switch (c) {
                    case 'n': sb.append('\n'); break;
                    case 't': sb.append('\t'); break;
                    case 'r': sb.append('\r'); break;
                    case '"': sb.append('"'); break;
                    case '\\': sb.append('\\'); break;
                    case '/': sb.append('/'); break;
                    default: sb.append(c);
                }
                escape = false;
            } else if (c == '\\') {
                escape = true;
            } else if (c == '"') {
                return sb.toString();
            } else {
                sb.append(c);
            }
            q2++;
        }
        return sb.toString();
    }
}