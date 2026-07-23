package com.clawbot.wechatbot.tools.searchonlinetool;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 通道B：必应国内版 HTML 抓取兜底。
 *
 * 无需 API Key，伪装浏览器 UA 发 GET 请求，用正则从 HTML 里抽结果。
 */
class BingHtmlSearchChannel implements WebSearchTool.SearchChannel {

    private static final String BING_URL = "https://cn.bing.com/search";

    private static final Pattern BING_ALGO_BLOCK = Pattern.compile(
        "<li[^>]*class=\"[^\"]*b_algo[^\"]*\"[^>]*>(.*?)</li>", Pattern.DOTALL);
    private static final Pattern BING_TITLE = Pattern.compile(
        "<h2[^>]*>\\s*<a[^>]*href=\"([^\"]+)\"[^>]*>(.*?)</a>\\s*</h2>", Pattern.DOTALL);
    private static final Pattern BING_SNIPPET = Pattern.compile(
        "<(?:p|span|div)[^>]*class=\"[^\"]*(?:b_snippet|snippet|b_caption)[^\"]*\"[^>]*>(.*?)</(?:p|span|div)>",
        Pattern.DOTALL);

    private final SearchHttpClient http;

    BingHtmlSearchChannel(SearchHttpClient http) {
        this.http = http;
    }

    @Override
    public String name() {
        return "必应HTML搜索";
    }

    @Override
    public List<WebSearchTool.SearchResult> search(String query, int count) throws Exception {
        String url = BING_URL + "?q=" + URLEncoder.encode(query, StandardCharsets.UTF_8);
        String html = http.getHtmlAsBrowser(url);
        if (html == null) {
            throw new RuntimeException("必应搜索未能返回内容");
        }
        return parseBingHtml(html, count);
    }

    private static List<WebSearchTool.SearchResult> parseBingHtml(String html, int count) {
        List<WebSearchTool.SearchResult> results = new ArrayList<>();
        Matcher blockMatcher = BING_ALGO_BLOCK.matcher(html);
        while (blockMatcher.find() && results.size() < count) {
            String block = blockMatcher.group(1);

            String title = "";
            String link = "";
            Matcher titleMatcher = BING_TITLE.matcher(block);
            if (titleMatcher.find()) {
                link = titleMatcher.group(1).trim();
                title = stripHtmlTags(titleMatcher.group(2)).trim();
            }

            String snippet = "";
            Matcher snippetMatcher = BING_SNIPPET.matcher(block);
            if (snippetMatcher.find()) {
                snippet = stripHtmlTags(snippetMatcher.group(1)).trim();
            }

            if (!title.isEmpty() || !snippet.isEmpty()) {
                results.add(new WebSearchTool.SearchResult(title, snippet, link));
            }
        }
        return results;
    }

    private static String stripHtmlTags(String s) {
        if (s == null) return "";
        String t = s.replaceAll("<[^>]+>", "");
        t = t.replace("&nbsp;", " ").replace("&amp;", "&")
            .replace("&lt;", "<").replace("&gt;", ">")
            .replace("&quot;", "\"").replace("&#39;", "'");
        return t.replaceAll("\\s+", " ").trim();
    }
}