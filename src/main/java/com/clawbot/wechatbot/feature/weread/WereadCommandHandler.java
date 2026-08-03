package com.clawbot.wechatbot.feature.weread;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
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
            String keyword = extractKeyword(text);
            if (keyword == null || keyword.isBlank()) {
                return "❌ 没听懂要搜什么书，试试：搜一下 三体";
            }
            return formatSearch(keyword);
        }
        if (containsAny(text, "推荐") && containsAny(text, "书", "读")) {
            return formatRecommend();
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
                if (book.path("finishReading").asInt(0) == 1) out.append("（已读完）");
                out.append('\n');
            }
        }
        int albums = root.path("albums").isArray() ? root.path("albums").size() : 0;
        StringBuilder result = new StringBuilder("📚 我的书架（").append(count).append(" 本");
        if (albums > 0) result.append(" + 🎧").append(albums).append(" 有声");
        result.append("）\n").append(out);
        return result.toString().trim();
    }

    // ---- 阅读统计 ----

    private String formatReadData(String mode) throws Exception {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("mode", mode);
        JsonNode root = gateway.call("/readdata/detail", params);
        StringBuilder out = new StringBuilder("📖 阅读统计（")
            .append(modeLabel(mode)).append("）\n");
        out.append("· 阅读天数：").append(root.path("readDays").asInt(0)).append(" 天\n");
        long seconds = root.path("totalReadTime").asLong(0);
        out.append("· 阅读时长：").append(formatDuration(seconds)).append('\n');
        JsonNode rank = root.path("rank");
        String rankText = text(rank, "text", "");
        if (!rankText.isBlank()) out.append("· 排名：").append(rankText).append('\n');
        JsonNode prefs = root.path("preferBooks");
        if (prefs.isArray() && prefs.size() > 0) {
            out.append("· 偏好标签：");
            StringBuilder tags = new StringBuilder();
            for (JsonNode pref : prefs) {
                String t = text(pref, "title", "");
                if (!t.isBlank()) {
                    if (tags.length() > 0) tags.append(" / ");
                    tags.append(t);
                }
            }
            out.append(tags).append('\n');
        }
        return out.toString().trim();
    }

    // ---- 笔记划线 ----

    private String formatNotebooks() throws Exception {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("count", 10);
        JsonNode root = gateway.call("/user/notebooks", params);
        StringBuilder out = new StringBuilder("✏️ 我的划线笔记\n");
        int totalNotes = root.path("totalNoteCount").asInt(0);
        out.append("（共 ").append(totalNotes).append(" 条笔记）\n");
        JsonNode books = root.path("books");
        String firstBookId = null;
        int firstBookNotes = 0;
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
                    firstBookNotes = noteCount;
                }
            }
        }
        // 取第一本有笔记的书，展示最近划线内容
        if (firstBookId != null && !firstBookId.isBlank()) {
            Map<String, Object> marks = new LinkedHashMap<>();
            marks.put("bookId", firstBookId);
            JsonNode markRoot = gateway.call("/book/bookmarklist", marks);
            JsonNode updated = markRoot.path("updated");
            int shown = 0;
            if (updated.isArray()) {
                for (JsonNode mark : updated) {
                    if (shown >= 3) break;
                    String markText = text(mark, "markText", "");
                    if (!markText.isBlank()) {
                        out.append("  • ").append(truncate(markText, 60)).append('\n');
                        shown++;
                    }
                }
            }
            if (shown == 0 && firstBookNotes > 0) {
                out.append("  （该书的划线内容暂不可导出，共 ")
                    .append(firstBookNotes).append(" 条笔记）\n");
            }
        }
        return out.toString().trim();
    }

    // ---- 搜索 ----

    private String formatSearch(String keyword) throws Exception {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("keyword", keyword);
        params.put("scope", 10);
        JsonNode root = gateway.call("/store/search", params);
        StringBuilder out = new StringBuilder("🔍 搜索《")
            .append(keyword).append("》结果\n");
        JsonNode results = root.path("results");
        int count = 0;
        if (results.isArray()) {
            for (JsonNode group : results) {
                JsonNode books = group.path("books");
                if (!books.isArray()) continue;
                for (JsonNode item : books) {
                    if (count >= 5) break;
                    JsonNode info = item.path("bookInfo");
                    if (info.isMissingNode()) info = item;
                    count++;
                    out.append(count).append(". ").append(text(info, "title", "未知"));
                    String author = text(info, "author", "");
                    if (!author.isBlank()) out.append(" - ").append(author);
                    String deepLink = text(info, "deepLink", "");
                    if (!deepLink.isBlank()) out.append("\n   ").append(deepLink);
                    out.append('\n');
                }
            }
        }
        if (count == 0) out.append("没有找到相关书籍");
        return out.toString().trim();
    }

    // ---- 推荐 ----

    private String formatRecommend() throws Exception {
        JsonNode root = gateway.call("/book/recommend", Map.of());
        StringBuilder out = new StringBuilder("🎯 为你推荐\n");
        JsonNode books = root.path("books");
        int count = 0;
        if (books.isArray()) {
            for (JsonNode book : books) {
                if (count >= 3) break;
                count++;
                out.append(count).append(". ").append(text(book, "title", "未知"));
                String author = text(book, "author", "");
                String category = text(book, "category", "");
                if (!author.isBlank()) out.append(" - ").append(author);
                if (!category.isBlank()) out.append("（").append(category).append("）");
                String intro = text(book, "intro", "");
                if (!intro.isBlank()) out.append("\n   ").append(truncate(intro, 60));
                String deepLink = text(book, "deepLink", "");
                if (!deepLink.isBlank()) out.append("\n   ").append(deepLink);
                out.append('\n');
            }
        }
        if (count == 0) out.append("暂时没有推荐");
        return out.toString().trim();
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
        if (containsAny(text, "月", "本月", "这个月")) return "monthly";
        if (containsAny(text, "年", "今年", "年度")) return "yearly";
        if (containsAny(text, "总", "一共", "所有")) return "overall";
        return "weekly";
    }

    /** 搜索关键词提取：去掉命令前缀词后取剩余文本。 */
    private static String extractKeyword(String text) {
        String cleaned = text
            .replaceAll("^(?:帮我|请|我想|我要)?\\s*", "")
            .replaceAll("(?:搜一下|搜索|搜搜|找一下|找找|帮我找|查一下|查查|查|找|搜)", "")
            .replaceAll("(?:有没有|有什么|那本|这本书|书)", "")
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
            case "yearly" -> "今年";
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

    private static String truncate(String value, int max) {
        if (value == null) return "";
        String oneLine = value.replaceAll("\\s+", " ").trim();
        return oneLine.length() <= max ? oneLine : oneLine.substring(0, max) + "…";
    }
}
