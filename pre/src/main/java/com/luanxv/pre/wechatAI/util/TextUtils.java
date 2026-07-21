package com.luanxv.pre.wechatAI.util;

import java.util.regex.Pattern;

public final class TextUtils {
    private static final Pattern IMAGE_GEN_PATTERN = Pattern.compile(
            "(\u751f\u6210|\u753b|\u521b\u5efa|\u5236\u4f5c|\u7ed9\u6211|\u5e2e\u6211).{0,10}"
                    + "(\u56fe\u7247|\u753b|\u7167\u7247|\u56fe\u50cf|image|picture)",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern IMAGE_EDIT_PATTERN = Pattern.compile(
            "(\u4e0a\u4e00\u5f20|\u4e0a\u5f20|\u521a\u624d|\u521a\u521a|\u8fd9\u5f20|\u8fd9\u5e45|\u8be5).{0,12}"
                    + "(\u56fe\u7247|\u56fe|\u7167\u7247).{0,40}"
                    + "(\u6539|\u4fee\u6539|\u8c03\u6574|\u6362|\u53d8\u6210|\u52a0|\u6dfb\u52a0|\u53bb\u6389|\u5220\u9664|\u79fb\u9664|\u66ff\u6362|\u7f8e\u5316|\u653e\u5927|\u7f29\u5c0f|\u589e\u5f3a)"
    );
    private static final Pattern IMAGE_EDIT_FOLLOW_UP_PATTERN = Pattern.compile(
            "(\u4e0d\u592a\u6ee1\u610f|\u4e0d\u6ee1\u610f|\u518d).{0,16}"
                    + "(\u6539|\u4fee\u6539|\u8c03\u6574|\u6362|\u53d8\u6210|\u52a0|\u6dfb\u52a0|\u53bb\u6389|\u5220\u9664|\u79fb\u9664|\u66ff\u6362|\u7f8e\u5316|\u653e\u5927|\u7f29\u5c0f|\u589e\u5f3a)"
    );
    private static final Pattern WEB_SEARCH_PATTERN = Pattern.compile(
            "(?i)(?:"
                    + "(?:\u8054\u7f51|\u7f51\u4e0a|\u4e92\u8054\u7f51).{0,8}(?:\u641c\u7d22|\u67e5|\u67e5\u627e|\u641c)"
                    + "|(?:\u5929\u6c14|\u65b0\u95fb|\u80a1\u4ef7|\u80a1\u7968|\u6c47\u7387|\u4ef7\u683c|\u6cb9\u4ef7|\u91d1\u4ef7|\u6bd4\u5206|\u8d5b\u7a0b|\u822a\u73ed|\u8def\u51b5|\u7968\u623f|\u653f\u7b56|\u6cd5\u89c4)"
                    + "|(?:latest|current|today|tomorrow|weather|news|stock|exchange\\s*rate|score|schedule)"
                    + ")"
    );

    private static final Pattern IMAGE_EDIT_ACTION_PATTERN = Pattern.compile(
            "(?:\u6539|\u4fee\u6539|\u8c03\u6574|\u6362|\u53d8\u6210|\u52a0|\u6dfb\u52a0|\u53bb\u6389|\u5220\u9664|\u79fb\u9664|\u66ff\u6362|\u7f8e\u5316|\u653e\u5927|\u7f29\u5c0f|\u589e\u5f3a)"
    );
    private static final Pattern NON_IMAGE_EDIT_PATTERN = Pattern.compile(
            "(?i)(?:\u4ee3\u7801|\u7a0b\u5e8f|\u6587\u672c|\u6587\u7ae0|\u4f5c\u6587|\u7b80\u5386|word|excel|sql|java|python)"
    );

    private TextUtils() {
    }

    public static boolean isImageGenerationRequest(String text) {
        return text != null && !text.isBlank() && IMAGE_GEN_PATTERN.matcher(text).find();
    }

    public static boolean isImageEditRequest(String text) {
        return text != null && !text.isBlank()
                && (IMAGE_EDIT_PATTERN.matcher(text).find() || IMAGE_EDIT_FOLLOW_UP_PATTERN.matcher(text).find());
    }

    /** Uses the accompanying image as the editing target when a modification action is present. */
    public static boolean isImageEditInstruction(String text) {
        return text != null && !text.isBlank()
                && !NON_IMAGE_EDIT_PATTERN.matcher(text).find()
                && (isImageEditRequest(text) || IMAGE_EDIT_ACTION_PATTERN.matcher(text).find());
    }

    /** Returns true only for queries whose answer is likely to require current web data. */
    public static boolean isWebSearchRequest(String text) {
        return text != null && !text.isBlank() && WEB_SEARCH_PATTERN.matcher(text).find();
    }

    public static String cleanImagePrompt(String text) {
        String cleaned = text.replaceAll("(\u5e2e\u6211|\u751f\u6210|\u4e00\u5f20|\u56fe\u7247|\u56fe\u50cf|\u521b\u5efa|\u5236\u4f5c|\u7ed9\u6211|\u8bf7)", "").trim();
        return cleaned.isEmpty() ? "\u4e00\u53ea\u53ef\u7231\u7684\u5c0f\u732b" : cleaned;
    }
}
