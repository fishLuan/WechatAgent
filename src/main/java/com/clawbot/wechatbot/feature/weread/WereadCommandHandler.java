package com.clawbot.wechatbot.feature.weread;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 微信读书命令路由：解析自然语言意图，调网关并格式化为微信可读文本。
 *
 * <p>支持：书架查看、阅读统计（周/月/年/总）、笔记划线、搜索书籍、推荐好书。
 * 网关调用规范：业务参数平铺 JSON 顶层（由 {@link WereadGatewayClient} 保证）。</p>
 */
@Component
public final class WereadCommandHandler {
    private final WereadGatewayClient gateway;

    public WereadCommandHandler(WereadGatewayClient gateway) {
        this.gateway = gateway;
    }

    public String handle(String input) throws Exception {
        if (input == null || input.isBlank()) {
            return "❌ 请输入要查询的内容（如：看看我的书架 / 这周读了多少 / 我的划线笔记）";
        }
        String text = input.trim();

        if (containsAny(text, "书架")) {
            return formatShelf();
        }
        if (containsAny(text, "笔记", "划线", "标注")) {
            return formatNotebooks();
        }
        if (containsAny(text, "读了多少", "阅读统计", "阅读报告", "了多久", "读了几本")) {
            return formatReadData(modeOf(text));
        }
        if (containsAny(text, "搜", "找", "查")) {
            return formatSearch(text);
        }
        if (containsAny(text, "推荐") && containsAny(text, "书", "读", "小说", "文学", "读物", "看书", "阅读")) {
            return formatRecommend(text);
        }
        return "❌ 未识别的微信读书指令。试试：书架 / 读了多少 / 划线笔记 / 搜一下 三体 / 推荐书";
    }

    // ---- 书架 ----

    private String formatShelf() throws Exception {
        JsonNode root = gateway.call("/shelf/sync", Map.of());
        StringBuilder out = new StringBuilder();
        JsonNode books = root.path("books");
        int count = 0;
        if (books.isArray()) {
            for (JsonNode book : books) {
                count++;
                out.append(count).append(". ").append(text(book, "title", "未知书名"));
                String author = text(book, "author", "");
                if (!author.isBlank()) out.append(" - ").append(author);
                if (book.path("finishReading").asInt(0) == 1) out.append(" ✅");
                String deepLink = text(book, "deepLink", "");
                if (!deepLink.isBlank()) out.append("\n[打开阅读](").append(deepLink).append(")");
                out.append("\n\n");
            }
        }
        int albums = root.path("albums").isArray() ? root.path("albums").size() : 0;
        StringBuilder result = new StringBuilder("📚 你的专属书架（").append(count).append(" 本");
        if (albums > 0) result.append(" + 🎧").append(albums).append(" 有声");
        result.append("）\n");
        if (count == 0) result.append("书架空空如也～快去逛逛书城吧 🛒\n");
        result.append(out);
        return result.toString().trim();
    }

    // ---- 阅读统计 ----

    private String formatReadData(String mode) throws Exception {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("mode", mode);
        JsonNode root = gateway.call("/readdata/detail", params);
        StringBuilder out = new StringBuilder("📖 阅读统计（")
            .append(modeLabel(mode)).append("）\n");

        // 一、阅读概览
        int readDays = root.path("readDays").asInt(0);
        long totalSeconds = root.path("totalReadTime").asLong(0);
        out.append("· 阅读天数：").append(readDays).append(" 天\n");
        out.append("· 总阅读时长：").append(formatDuration(totalSeconds)).append('\n');
        long dayAvg = root.path("dayAverageReadTime").asLong(0);
        if (dayAvg > 0) out.append("· 日均阅读：").append(formatDuration(dayAvg)).append('\n');

        // 与上期对比
        double compare = root.path("compare").asDouble(0);
        if (compare != 0) {
            String trend = compare > 0 ? "📈 增长" : "📉 下降";
            out.append("· 较上周期：").append(trend).append(String.format("%.0f%%", Math.abs(compare * 100))).append('\n');
        }

        // 文字/听书占比
        if (!root.path("readRate").isMissingNode()) {
            int readRate = root.path("readRate").asInt(0);
            out.append("· 文字阅读占比：").append(readRate).append("%（文字")
                .append(formatDuration(root.path("wrReadTime").asLong(0)))
                .append(" / 听书").append(formatDuration(root.path("wrListenTime").asLong(0)))
                .append("）\n");
        }

        // 二、阅读统计
        JsonNode readStat = root.path("readStat");
        if (readStat.isArray() && readStat.size() > 0) {
            out.append("\n📊 数据概览\n");
            for (JsonNode item : readStat) {
                String stat = text(item, "stat", "");
                String counts = text(item, "counts", "");
                if (!stat.isBlank() && !counts.isBlank()) {
                    out.append("· ").append(stat).append("：").append(counts).append('\n');
                }
            }
        }

        // 三、偏好分类
        String categoryWord = text(root, "preferCategoryWord", "");
        if (!categoryWord.isBlank()) {
            out.append("\n📂 ").append(categoryWord).append('\n');
            JsonNode categories = root.path("preferCategory");
            if (categories.isArray()) {
                StringBuilder cats = new StringBuilder();
                for (JsonNode cat : categories) {
                    String name = text(cat, "categoryTitle", "");
                    if (!name.isBlank()) {
                        if (cats.length() > 0) cats.append(" / ");
                        cats.append(name);
                    }
                }
                if (cats.length() > 0) out.append("· ").append(cats).append('\n');
            }
        }

        // 四、偏好时段
        String timeWord = text(root, "preferTimeWord", "");
        if (!timeWord.isBlank()) out.append("· ⏰ ").append(timeWord).append('\n');

        // 五、偏好作者
        JsonNode authors = root.path("preferAuthor");
        if (authors.isArray() && authors.size() > 0) {
            out.append("\n✍️ 偏好的作者\n");
            int authorCount = root.path("authorCount").asInt(0);
            for (int i = 0; i < Math.min(authors.size(), 3); i++) {
                JsonNode a = authors.get(i);
                String name = text(a, "name", "");
                String time = text(a, "readTime", "");
                int count = a.path("count").asInt(0);
                if (!name.isBlank()) {
                    out.append("· ").append(name).append("（").append(count).append("本");
                    if (!time.isBlank()) out.append(" / ").append(time);
                    out.append("）\n");
                }
            }
            if (authorCount > 3) out.append("  …等 ").append(authorCount).append(" 位作者\n");
        }

        // 六、排名
        JsonNode rank = root.path("rank");
        String rankText = text(rank, "text", "");
        if (!rankText.isBlank()) out.append("\n🏆 ").append(rankText).append('\n');

        return out.toString().trim();
    }

    // ---- 笔记划线 ----

    private String formatNotebooks() throws Exception {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("count", 10);
        JsonNode root = gateway.call("/user/notebooks", params);
        StringBuilder out = new StringBuilder("✏️ 你的读书印记\n");
        int totalNotes = root.path("totalNoteCount").asInt(0);
        out.append("共 ").append(totalNotes).append(" 条笔记，记录着你的思考 💭\n");
        JsonNode books = root.path("books");
        String firstBookId = null;
        if (books.isArray()) {
            for (JsonNode entry : books) {
                JsonNode book = entry.path("book");
                String title = text(book, "title", "未知书名");
                int noteCount = entry.path("noteCount").asInt(0)
                    + entry.path("reviewCount").asInt(0)
                    + entry.path("bookmarkCount").asInt(0);
                if (noteCount > 0) {
                    out.append("\n《").append(title).append("》（")
                        .append(noteCount).append(" 条）\n");
                }
                if (firstBookId == null && noteCount > 0) {
                    firstBookId = text(book, "bookId", "");
                }
            }
        }
        // 取第一本有笔记的书，展示最近划线内容
        if (firstBookId != null && !firstBookId.isBlank()) {
            Map<String, Object> marks = new LinkedHashMap<>();
            marks.put("bookId", firstBookId);
            JsonNode markRoot = gateway.call("/book/bookmarklist", marks);

            // 构建章节 UID → 章节标题映射
            Map<Integer, String> chapterMap = new LinkedHashMap<>();
            JsonNode chapters = markRoot.path("chapters");
            if (chapters.isArray()) {
                for (JsonNode ch : chapters) {
                    chapterMap.put(ch.path("chapterUid").asInt(0),
                        text(ch, "title", ""));
                }
            }

            // 按章节分组展示划线，引用格式
            JsonNode updated = markRoot.path("updated");
            int shown = 0;
            if (updated.isArray()) {
                String lastChapter = "";
                for (JsonNode mark : updated) {
                    if (shown >= 5) break;
                    String markText = text(mark, "markText", "");
                    if (markText.isBlank()) continue;
                    int chapterUid = mark.path("chapterUid").asInt(0);
                    String chapterTitle = chapterMap.getOrDefault(chapterUid, "");
                    if (!chapterTitle.isEmpty() && !chapterTitle.equals(lastChapter)) {
                        out.append("\n【").append(chapterTitle).append("】\n");
                        lastChapter = chapterTitle;
                    }
                    out.append("> ").append(markText).append("\n");
                    shown++;
                }
            }
            if (shown == 0) {
                out.append("（暂无划线内容）\n");
            }
        }
        return out.toString().trim();
    }

    // ---- 推荐 ----

    private String formatRecommend(String input) throws Exception {
        String rawReason = extractReason(input);
        String reason = normalizeGenreKeyword(rawReason);

        // 有具体主题 → 走 /store/search 搜索（/book/recommend 不支持筛选参数）
        if (!reason.isBlank()) {
            return formatSearchByReason(reason);
        }

        // 无主题 → 通用个性化推荐
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("count", 3);
        JsonNode root = gateway.call("/book/recommend", params);
        String[] picks = {"这几本可能对你胃口 😋", "猜你喜欢 👀", "为你精挑细选 ✨", "发现了一些宝藏 📦"};
        StringBuilder out = new StringBuilder("🎯 ").append(randomPick(picks)).append("\n");
        JsonNode books = root.path("books");
        int count = 0;
        if (books.isArray()) {
            for (JsonNode book : books) {
                if (count >= 3) break;
                count++;
                out.append(count).append(". ").append(text(book, "title", "未知"));
                String author = text(book, "author", "");
                if (!author.isBlank()) out.append(" - ").append(author);
                int rating = book.path("newRating").asInt(0);
                if (rating > 0) out.append(" ⭐").append(String.format("%.1f", rating / 20.0));
                int readers = book.path("readingCount").asInt(0);
                if (readers > 0) out.append(" ").append(formatCount(readers)).append("人在读");
                String recommendReason = text(book, "reason", "");
                if (!recommendReason.isBlank()) out.append("\n💬 ").append(recommendReason);
                String intro = text(book, "intro", "");
                if (!intro.isBlank()) out.append("\n").append(truncate(intro, 60));
                String deepLink = text(book, "deepLink", "");
                if (!deepLink.isBlank()) {
                    out.append("\n[打开阅读](").append(deepLink).append(")");
                }
                out.append("\n");
            }
        }
        if (count == 0) out.append("暂时没有推荐 🤷 换个主题试试？");
        return out.toString().trim();
    }

    /** 有主题推荐直接走搜索，用搜索结果的格式输出。 */
    private String formatSearchByReason(String keyword) throws Exception {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("keyword", keyword);
        params.put("scope", 10);
        params.put("count", 3);
        JsonNode root = gateway.call("/store/search", params);
        StringBuilder out = new StringBuilder("🎯「").append(keyword).append("」主题书单来啦～\n");
        JsonNode results = root.path("results");
        int count = 0;
        if (results.isArray()) {
            for (JsonNode group : results) {
                JsonNode books = group.path("books");
                if (!books.isArray()) continue;
                for (JsonNode book : books) {
                    if (count >= 5) break;
                    count++;
                    JsonNode info = book.path("bookInfo");
                    String title = text(info, "title", "未知");
                    out.append(count).append(". ").append(title);
                    if (info.path("soldout").asInt(0) == 1) out.append("（已下架）");
                    String author = text(info, "author", "");
                    if (!author.isBlank()) out.append(" - ").append(author);
                    int rating = book.path("newRating").asInt(0);
                    if (rating > 0) out.append(" ⭐").append(String.format("%.1f", rating / 20.0));
                    JsonNode ratingDetail = book.path("newRatingDetail");
                    String ratingTag = text(ratingDetail, "title", "");
                    if (!ratingTag.isBlank()) out.append(" 🏷").append(ratingTag);
                    String intro = text(info, "intro", "");
                    if (!intro.isBlank()) out.append("\n").append(truncate(intro, 80));
                    String deepLink = text(info, "deepLink", "");
                    if (!deepLink.isBlank()) {
                        out.append("\n[打开阅读](").append(deepLink).append(")");
                    }
                    out.append("\n\n");
                }
            }
        }
        if (count == 0) out.append("没找到这个主题的书 😅 试试发送「推荐书」获取通用推荐～");
        return out.toString().trim();
    }

    /** 标准化推荐关键词，去掉冗余词汇并转换为更标准的搜索词。 */
    private static String normalizeGenreKeyword(String text) {
        if (text == null || text.isBlank()) return "";
        String cleaned = text
            .replaceAll("(?:风格|类|类型|方面|领域|的书|的|之类|一点|一些|几本书|几本)", "")
            .replaceAll("\\s+", " ")
            .trim();
        
        // 常见类型标准化映射
        Map<String, String> mappings = new LinkedHashMap<>();
        mappings.put("科幻", "科幻小说");
        mappings.put("奇幻", "奇幻小说");
        mappings.put("玄幻", "玄幻小说");
        mappings.put("武侠", "武侠小说");
        mappings.put("言情", "言情小说");
        mappings.put("悬疑", "悬疑小说");
        mappings.put("推理", "推理小说");
        mappings.put("恐怖", "恐怖小说");
        mappings.put("惊悚", "惊悚小说");
        mappings.put("历史", "历史小说");
        mappings.put("心理学", "心理学书籍");
        mappings.put("哲学", "哲学书籍");
        mappings.put("经济学", "经济学书籍");
        mappings.put("编程", "编程技术书籍");
        mappings.put("技术", "技术书籍");
        mappings.put("自我提升", "自我成长书籍");
        mappings.put("成功学", "成功励志书籍");
        mappings.put("管理", "管理学书籍");
        mappings.put("传记", "人物传记");
        
        // 按长度倒序匹配，优先匹配更长的键
        String bestMatch = "";
        for (Map.Entry<String, String> entry : mappings.entrySet()) {
            String key = entry.getKey();
            if (cleaned.contains(key) && key.length() > bestMatch.length()) {
                bestMatch = key;
            }
        }
        if (!bestMatch.isEmpty()) {
            return mappings.get(bestMatch);
        }
        return cleaned;
    }

    /** 判断是否是具体的书籍类型/主题（而不是泛泛的推荐请求）。 */
    private static boolean isSpecificGenre(String text) {
        String[] genres = {
            "科幻", "奇幻", "玄幻", "武侠", "言情", "悬疑", "推理", "恐怖", "惊悚",
            "历史", "传记", "文学", "小说", "诗歌", "散文", "哲学", "心理学",
            "经济学", "管理", "编程", "技术", "科学", "艺术", "音乐", "电影",
            "旅行", "美食", "健康", "育儿", "教育", "自我提升", "成功学",
            "自我成长", "励志"
        };
        String lower = text.toLowerCase();
        for (String genre : genres) {
            if (lower.contains(genre)) {
                return true;
            }
        }
        return false;
    }

    // ---- 搜索 ----

    private String formatSearch(String input) throws Exception {
        String keyword = extractSearchQuery(input);
        // 关键词为空或太泛时，直接走推荐
        if (keyword.isBlank() || keyword.equals("书")) {
            return formatRecommend(input);
        }
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("keyword", keyword);
        params.put("scope", 10);
        params.put("count", 5);
        JsonNode root = gateway.call("/store/search", params);
        StringBuilder out = new StringBuilder("🔍「").append(keyword).append("」的搜索结果～\n");
        JsonNode results = root.path("results");
        int count = 0;
        if (results.isArray()) {
            for (JsonNode group : results) {
                JsonNode books = group.path("books");
                if (!books.isArray()) continue;
                for (JsonNode book : books) {
                    if (count >= 5) break;
                    count++;
                    JsonNode info = book.path("bookInfo");
                    String title = text(info, "title", "未知");
                    out.append(count).append(". ").append(title);
                    if (info.path("soldout").asInt(0) == 1) out.append("（已下架）");
                    String author = text(info, "author", "");
                    if (!author.isBlank()) out.append(" - ").append(author);
                    int rating = book.path("newRating").asInt(0);
                    if (rating > 0) out.append(" ⭐").append(String.format("%.1f", rating / 20.0));
                    JsonNode ratingDetail = book.path("newRatingDetail");
                    String ratingTag = text(ratingDetail, "title", "");
                    if (!ratingTag.isBlank()) out.append(" 🏷").append(ratingTag);
                    String intro = text(info, "intro", "");
                    if (!intro.isBlank()) out.append("\n").append(truncate(intro, 80));
                    String deepLink = text(info, "deepLink", "");
                    if (!deepLink.isBlank()) {
                        out.append("\n[打开阅读](").append(deepLink).append(")");
                    }
                    out.append("\n\n");
                }
            }
        }
        if (count == 0) out.append("没找到相关书籍 😅 换个关键词试试？");
        return out.toString().trim();
    }

    /** 搜索关键词提取：去掉命令词后取剩余文本（如"搜一下三体"→"三体"）。 */
    private static String extractSearchQuery(String text) {
        String cleaned = text
            .replaceAll("^(?:帮我|请|我想|我要|给我|来)?\\s*", "")
            .replaceAll("(?:搜索|搜一下|搜一搜|搜|找一下|找一找|找|查一下|查一查|查|一下|几本|一本|看看|有没有|关于|有关)", "")
            .replaceAll("(?:书籍|图书|书|推荐|介绍)", "")
            .replaceAll("(?:风格|类|类型|方面|领域|的|之类|一点|一些)", "")
            .replaceAll("\\s+", " ")
            .trim();
        return cleaned;
    }

    // ---- 工具方法 ----

    private static boolean containsAny(String text, String... keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword)) return true;
        }
        return false;
    }

    /** 统计周期推断：月/年/总，默认周。 */
    private static String modeOf(String text) {
        if (containsAny(text, "这周", "本周")) return "weekly";
        if (containsAny(text, "月", "本月", "这个月")) return "monthly";
        if (containsAny(text, "年", "今年", "年度")) return "annually";
        if (containsAny(text, "总", "一共", "所有")) return "overall";
        return "overall";
    }

    /** 推荐描述提取：去掉命令词后取剩余文本（如"推荐几本心理学的书"→"心理学"）。 */
    private static String extractReason(String text) {
        String cleaned = text
            .replaceAll("^(?:帮我|请|我想|我要|给我|来)?\\s*", "")
            .replaceAll("(?:推荐|介绍|有没有|几本|一本|什么|哪些|一些|帮|找|搜|查|看看|关于|有关)", "")
            .replaceAll("(?:书|读|看|想|要|读一下)", "")
            .replaceAll("\\s+", " ")
            .trim();
        return cleaned;
    }

    private static String formatDuration(long seconds) {
        if (seconds <= 0) return "0 分钟";
        long minutes = seconds / 60;
        if (minutes < 60) return minutes + " 分钟";
        long hours = minutes / 60;
        long rest = minutes % 60;
        return rest == 0 ? hours + " 小时" : hours + " 小时 " + rest + " 分";
    }

    private static String modeLabel(String mode) {
        return switch (mode) {
            case "monthly" -> "本月";
            case "annually" -> "今年";
            case "overall" -> "总计";
            default -> "本周";
        };
    }

    private static String text(JsonNode node, String field, String fallback) {
        if (node == null || node.isMissingNode() || node.isNull()) return fallback;
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull()) return fallback;
        return value.asText(fallback);
    }

    /** 随机取一个数组元素，用于丰富回复语气。 */
    private static String randomPick(String[] options) {
        return options[(int) (System.currentTimeMillis() % options.length)];
    }

    private static String truncate(String value, int max) {
        if (value == null) return "";
        String oneLine = value.replaceAll("\\s+", " ").trim();
        return oneLine.length() <= max ? oneLine : oneLine.substring(0, max) + "…";
    }

    /** 格式化大数字（如 12345 → "1.2万"）。 */
    private static String formatCount(int count) {
        if (count >= 10000) return String.format("%.1f万", count / 10000.0);
        if (count >= 1000) return String.format("%.1fk", count / 1000.0);
        return String.valueOf(count);
    }
}