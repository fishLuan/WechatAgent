package com.clawbot.wechatbot.tools.webPageTool;

/** 网页正文抓取工具入参。 */
public class WebPageExtractRequest {
    private final String url;
    private final int maxBodyChars;

    public WebPageExtractRequest(String url, int maxBodyChars) {
        this.url = url == null ? "" : url.trim();
        this.maxBodyChars = maxBodyChars;
    }

    public String getUrl() { return url; }
    public int getMaxBodyChars() { return maxBodyChars; }
}
