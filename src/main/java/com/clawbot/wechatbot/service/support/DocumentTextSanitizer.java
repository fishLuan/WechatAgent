package com.clawbot.wechatbot.service.support;

/** 清理文档字体通常无法渲染的 emoji 和控制字符。 */
public final class DocumentTextSanitizer {
    private DocumentTextSanitizer() {}

    public static String sanitize(String text) {
        if (text == null) return "";
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c < 0x80) {
                if (c == '\n' || c == '\r' || c == '\t' || c >= 32) result.append(c);
            } else if ((c >= 0x4E00 && c <= 0x9FFF)
                    || (c >= 0x3000 && c <= 0x303F)
                    || (c >= 0xFF00 && c <= 0xFFEF)) {
                result.append(c);
            }
        }
        return result.toString().trim();
    }
}
