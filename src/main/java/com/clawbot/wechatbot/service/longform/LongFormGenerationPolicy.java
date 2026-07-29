package com.clawbot.wechatbot.service.longform;

import java.util.OptionalInt;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 长文识别、目标长度和续写安全上限配置。 */
public record LongFormGenerationPolicy(
    boolean enabled,
    int minTargetChars,
    int maxTargetChars,
    int tolerancePercent,
    int maxContinuationRounds,
    int maxTotalChars
) {
    private static final Pattern TARGET_LENGTH_PATTERN =
        Pattern.compile("(?<!\\d)(\\d{2,6})\\s*(?:个)?(?:汉)?字(?:左右|以上|以内)?");

    public LongFormGenerationPolicy {
        if (minTargetChars < 1) {
            throw new IllegalArgumentException("长文触发字数必须大于 0");
        }
        if (maxTargetChars < minTargetChars) {
            throw new IllegalArgumentException("长文最大目标字数不能小于触发字数");
        }
        if (tolerancePercent < 0 || tolerancePercent >= 100) {
            throw new IllegalArgumentException("长文字数容差必须在 0 到 99 之间");
        }
        if (maxContinuationRounds < 0) {
            throw new IllegalArgumentException("长文续写轮数不能小于 0");
        }
        if (maxTotalChars < maxTargetChars) {
            throw new IllegalArgumentException("长文总字数上限不能小于最大目标字数");
        }
    }

    public OptionalInt targetChars(String userText) {
        if (!enabled || userText == null || userText.isBlank()) {
            return OptionalInt.empty();
        }
        Matcher matcher = TARGET_LENGTH_PATTERN.matcher(userText);
        if (!matcher.find()) return OptionalInt.empty();
        int requested = Integer.parseInt(matcher.group(1));
        if (requested < minTargetChars) return OptionalInt.empty();
        return OptionalInt.of(Math.min(requested, maxTargetChars));
    }

    public int lowerBound(int targetChars) {
        return Math.max(1, targetChars * (100 - tolerancePercent) / 100);
    }

    public static LongFormGenerationPolicy disabled() {
        return new LongFormGenerationPolicy(false, 1200, 10000, 10, 0, 12000);
    }
}
