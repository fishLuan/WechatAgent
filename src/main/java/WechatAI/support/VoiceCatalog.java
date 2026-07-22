package WechatAI.support;

import java.util.HashMap;
import java.util.Map;

/**
 * Qwen-TTS 音色目录，负责中文别名、展示名和模型 voice code 的映射。
 */
public final class VoiceCatalog {

    public static final String DEFAULT_VOICE = "Cherry";

    private static final Map<String, String> VOICE_ALIASES = createVoiceAliases();
    private static final Map<String, String> VOICE_DISPLAY_NAMES = createVoiceDisplayNames();

    private VoiceCatalog() {
    }

    public static String resolveVoice(String text, String defaultVoice) {
        String voice = findVoice(text);
        return voice == null ? defaultVoice : voice;
    }

    public static String findVoice(String text) {
        String normalized = text == null ? "" : text.trim().toLowerCase();
        for (Map.Entry<String, String> entry : VOICE_ALIASES.entrySet()) {
            if (normalized.contains(entry.getKey())) {
                return entry.getValue();
            }
        }
        return null;
    }

    public static String displayName(String voice) {
        return VOICE_DISPLAY_NAMES.getOrDefault(voice, voice);
    }

    private static Map<String, String> createVoiceAliases() {
        Map<String, String> aliases = new HashMap<>();
        addVoice(aliases, "Cherry", "女声", "女人", "女性", "女的", "小姐姐", "阳光", "亲切", "自然", "芊悦", "cherry");
        addVoice(aliases, "Serena", "温柔", "柔和", "苏瑶", "serena");
        addVoice(aliases, "Ethan", "男声", "男人", "男性", "男的", "阳光男声", "温暖男声", "晨煦", "ethan");
        addVoice(aliases, "Chelsie", "二次元", "虚拟女友", "千雪", "chelsie");
        addVoice(aliases, "Momo", "撒娇", "搞怪", "逗你开心", "茉兔", "momo");
        addVoice(aliases, "Vivian", "拽拽", "暴躁", "小暴躁", "十三", "vivian");
        addVoice(aliases, "Moon", "帅气", "率性", "月白", "moon");
        addVoice(aliases, "Maia", "知性", "四月", "maia");
        addVoice(aliases, "Kai", "磁性", "舒服", "凯", "kai");
        addVoice(aliases, "Nofish", "不翘舌", "设计师", "不吃鱼", "nofish");
        addVoice(aliases, "Bella", "萌宝", "萝莉", "小萝莉", "bella");
        addVoice(aliases, "Jennifer", "美语女声", "电影感女声", "詹妮弗", "jennifer");
        addVoice(aliases, "Ryan", "戏感", "张力", "甜茶", "ryan");
        addVoice(aliases, "Katerina", "御姐", "御姐音", "卡捷琳娜", "katerina");
        addVoice(aliases, "Aiden", "美语男声", "大男孩", "艾登", "aiden");
        addVoice(aliases, "Eldric Sage", "老者", "老人", "沉稳老者", "沧桑", "沧明子", "eldric", "eldric sage");
        addVoice(aliases, "Mia", "乖小妹", "乖巧", "温顺", "mia");
        addVoice(aliases, "Mochi", "小男孩", "男孩", "童声男", "沙小弥", "mochi");
        addVoice(aliases, "Bellona", "女侠", "江湖女声", "热血女声", "燕铮莺", "bellona");
        addVoice(aliases, "Vincent", "烟嗓", "沙哑", "田叔", "vincent");
        addVoice(aliases, "Bunny", "萌", "萌妹", "萌小姬", "bunny");
        addVoice(aliases, "Neil", "新闻", "播音", "主持", "主持人", "阿闻", "neil");
        addVoice(aliases, "Elias", "讲师", "老师", "教学", "墨讲师", "elias");
        addVoice(aliases, "Arthur", "大爷", "质朴", "徐大爷", "arthur");
        addVoice(aliases, "Nini", "邻家妹妹", "妹妹", "软萌", "nini");
        addVoice(aliases, "Seren", "晚安", "睡眠", "舒缓", "小婉", "seren");
        addVoice(aliases, "Pip", "调皮", "小孩", "顽皮", "顽屁小孩", "pip");
        addVoice(aliases, "Stella", "少女", "甜妹", "少女阿月", "stella");
        return aliases;
    }

    private static Map<String, String> createVoiceDisplayNames() {
        Map<String, String> names = new HashMap<>();
        names.put("Cherry", "芊悦/阳光女声");
        names.put("Serena", "苏瑶/温柔女声");
        names.put("Ethan", "晨煦/阳光男声");
        names.put("Chelsie", "千雪/二次元女声");
        names.put("Momo", "茉兔/撒娇搞怪女声");
        names.put("Vivian", "十三/拽拽女声");
        names.put("Moon", "月白/帅气男声");
        names.put("Maia", "四月/知性女声");
        names.put("Kai", "凯/磁性男声");
        names.put("Nofish", "不吃鱼/设计师男声");
        names.put("Bella", "萌宝/萝莉女声");
        names.put("Jennifer", "詹妮弗/美语女声");
        names.put("Ryan", "甜茶/戏感男声");
        names.put("Katerina", "卡捷琳娜/御姐音色");
        names.put("Aiden", "艾登/美语男声");
        names.put("Eldric Sage", "沧明子/沉稳老者");
        names.put("Mia", "乖小妹/乖巧女声");
        names.put("Mochi", "沙小弥/童声男");
        names.put("Bellona", "燕铮莺/热血女声");
        names.put("Vincent", "田叔/沙哑烟嗓");
        names.put("Bunny", "萌小姬/萌系女声");
        names.put("Neil", "阿闻/新闻主持");
        names.put("Elias", "墨讲师/讲师音色");
        names.put("Arthur", "徐大爷/质朴男声");
        names.put("Nini", "邻家妹妹/软萌女声");
        names.put("Seren", "小婉/舒缓晚安女声");
        names.put("Pip", "顽屁小孩/调皮童声");
        names.put("Stella", "少女阿月/甜美少女音");
        return names;
    }

    private static void addVoice(Map<String, String> aliases, String voice, String... keys) {
        for (String key : keys) {
            aliases.put(key.toLowerCase(), voice);
        }
    }
}
