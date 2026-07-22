package WechatAI.support;

/**
 * 文本意图识别与提示词清洗工具，集中维护用户自然语言触发规则。
 */
public final class TextIntentUtils {

    private TextIntentUtils() {
    }

    public static boolean isSpeechSynthesisRequest(String text) {
        String normalized = normalize(text);
        // 语音生成意图覆盖命令式、描述式和带音色风格的表达。
        boolean directCommand = normalized.startsWith("语音说")
                || normalized.startsWith("朗读")
                || normalized.startsWith("读一下")
                || normalized.startsWith("读出来")
                || normalized.startsWith("念一下")
                || normalized.startsWith("念出来")
                || normalized.startsWith("用语音说")
                || normalized.startsWith("配音");
        boolean asksAudio = normalized.contains("生成语音")
                || normalized.contains("生成一段语音")
                || normalized.contains("生成一个语音")
                || normalized.contains("生成音频")
                || normalized.contains("转成语音")
                || normalized.contains("转语音")
                || normalized.contains("转成音频")
                || normalized.contains("发语音")
                || normalized.contains("发个语音")
                || normalized.contains("做成语音")
                || normalized.contains("做个语音")
                || normalized.contains("配个音")
                || normalized.contains("配音")
                || normalized.contains("朗读")
                || normalized.contains("读出来")
                || normalized.contains("念出来")
                || normalized.contains("mp3")
                || normalized.contains("音频文件");
        boolean voiceSays = normalized.contains("语音")
                && (normalized.contains("内容是")
                || normalized.contains("说")
                || normalized.contains("读")
                || normalized.contains("念")
                || normalized.contains("生成")
                || normalized.contains("制作")
                || normalized.contains("做"));
        boolean styleSays = VoiceCatalog.findVoice(normalized) != null
                && (normalized.contains("说")
                || normalized.contains("读")
                || normalized.contains("念")
                || normalized.contains("生成")
                || normalized.contains("内容是"));
        return directCommand || asksAudio || voiceSays || styleSays;
    }

    public static boolean isVoicePreferenceRequest(String text) {
        String normalized = normalize(text);
        boolean mentionsVoice = normalized.contains("音色")
                || normalized.contains("声音")
                || VoiceCatalog.findVoice(normalized) != null;
        boolean asksSwitch = normalized.contains("切换")
                || normalized.contains("换成")
                || normalized.contains("改成")
                || normalized.contains("使用")
                || normalized.contains("用");
        return mentionsVoice && asksSwitch && !isSpeechSynthesisRequest(normalized);
    }

    public static boolean isClearMemoryRequest(String text) {
        String normalized = normalize(text);
        return "清空记忆".equals(normalized)
                || "忘记上下文".equals(normalized)
                || "重置对话".equals(normalized)
                || "清除记忆".equals(normalized);
    }

    public static boolean isImageGenerationRequest(String text) {
        String normalized = normalize(text);
        if (normalized.startsWith("画图")
                || normalized.startsWith("绘图")
                || normalized.startsWith("生成图片")
                || normalized.startsWith("生成一张图")
                || normalized.startsWith("帮我画")) {
            return true;
        }

        boolean asksToCreate = normalized.contains("生成")
                || normalized.contains("画")
                || normalized.contains("绘制")
                || normalized.contains("做一张")
                || normalized.contains("来一张");
        boolean targetIsImage = normalized.contains("图")
                || normalized.contains("图片")
                || normalized.contains("照片")
                || normalized.contains("海报")
                || normalized.contains("头像")
                || normalized.contains("壁纸");
        return asksToCreate && targetIsImage;
    }

    public static String normalizeSpeechPrompt(String text) {
        String prompt = normalize(text);
        // 依次剥离触发词、音色描述和文件格式描述，只保留需要朗读的正文。
        prompt = prompt.replaceFirst("^.*?内容是[:：,，\\s]*", "").trim();
        prompt = prompt.replaceFirst("^(请)?(帮我)?(给我)?(生成|制作|做|发)(一段|一条|一个|个)?", "").trim();
        prompt = prompt.replaceFirst("^(语音说|朗读|读一下|读出来|念一下|念出来|用语音说|配音)[:：,，\\s]*", "").trim();
        prompt = prompt.replaceFirst("^(用|使用|换成|改成)?[^，,。.!！?？]{0,12}(音色|声音|声线)(说|读|念)?[:：,，\\s]*", "").trim();
        prompt = prompt.replaceFirst("^(一段|一句|一条|一下)[:：,，\\s]*", "").trim();
        prompt = prompt.replaceFirst("^(说|读|念)(一段|一句|一条|一下)?[:：,，\\s]*", "").trim();
        prompt = prompt.replaceFirst("^.*?内容是[:：,，\\s]*", "").trim();
        prompt = prompt.replaceFirst("(的)?(语音|音频|mp3|音频文件)[。.!！?？\\s]*$", "").trim();
        return prompt.isEmpty() ? text : prompt;
    }

    public static String normalizeImagePrompt(String text) {
        String prompt = normalize(text).replaceFirst("^(画图|绘图|生成图片|生成一张图|帮我画)[:：,，\\s]*", "").trim();
        return prompt.isEmpty() ? text : prompt;
    }

    private static String normalize(String text) {
        return text == null ? "" : text.trim();
    }
}
