package com.clawbot.wechatbot.tools.webPageTool;

/** 网页正文抓取工具返回对象。 */
public class WebPageExtractResult {
    private final boolean success;
    private final String url;
    private final String finalUrl;
    private final int statusCode;
    private final String title;
    private final String description;
    private final String bodyText;
    private final String error;

    private WebPageExtractResult(boolean success, String url, String finalUrl, int statusCode,
                                 String title, String description, String bodyText, String error) {
        this.success = success;
        this.url = url == null ? "" : url;
        this.finalUrl = finalUrl == null ? "" : finalUrl;
        this.statusCode = statusCode;
        this.title = title == null ? "" : title;
        this.description = description == null ? "" : description;
        this.bodyText = bodyText == null ? "" : bodyText;
        this.error = error == null ? "" : error;
    }

    public static WebPageExtractResult ok(String url, String finalUrl, int statusCode,
                                          String title, String description, String bodyText) {
        return new WebPageExtractResult(true, url, finalUrl, statusCode, title, description, bodyText, "");
    }

    public static WebPageExtractResult error(String url, String message) {
        return new WebPageExtractResult(false, url, "", 0, "", "", "", message);
    }

    public boolean isSuccess() { return success; }
    public String getUrl() { return url; }
    public String getFinalUrl() { return finalUrl; }
    public int getStatusCode() { return statusCode; }
    public String getTitle() { return title; }
    public String getDescription() { return description; }
    public String getBodyText() { return bodyText; }
    public String getError() { return error; }
}
