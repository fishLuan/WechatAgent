package com.clawbot.wechatbot.service.agent.reference;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 指向当前任务图内某个可信任务输出字段的受限引用。 */
public record ResultReference(String taskId, String path, String expression) {
    private static final Pattern EXPRESSION = Pattern.compile(
        "^([A-Za-z0-9_-]{1,100})\\.output((?:\\.[\\p{L}\\p{N}_-]+|\\[\\d+])*)$");

    public static ResultReference parse(String expression, int maxPathLength) {
        String value = expression == null ? "" : expression.trim();
        if (value.length() > maxPathLength) {
            throw new ReferenceResolutionException(
                "REF_PATH_TOO_LONG", "$ref 路径超过 " + maxPathLength + " 字");
        }
        Matcher matcher = EXPRESSION.matcher(value);
        if (!matcher.matches()) {
            throw new ReferenceResolutionException(
                "REF_INVALID_FORMAT", "无效的 $ref 格式：" + value);
        }
        return new ResultReference(
            matcher.group(1), "$" + matcher.group(2), value);
    }
}
