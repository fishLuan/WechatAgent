package com.clawbot.wechatbot.util;

import java.awt.Desktop;
import java.io.File;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;

/**
 * 二维码显示工具：把扫码登录环节从主类里抽出来
 */
public final class QrCodeDisplay {

    private QrCodeDisplay() {}

    public static void display(String qrContent) {
        display(qrContent, "qrcode.html", "微信扫码登录");
    }

    public static void display(String qrContent, String fileName, String title) {
        if (qrContent == null || qrContent.trim().isEmpty()) {
            System.out.println("[WARN] 没有获取到二维码");
            return;
        }

        String content = qrContent.trim();
        System.out.println();
        System.out.println("扫码链接: " + content);
        System.out.println();

        // 直接是 URL → 在浏览器打开
        if (content.toLowerCase().startsWith("http://") || content.toLowerCase().startsWith("https://")) {
            openInBrowser(content);
            return;
        }

        // 其他格式（data URL / 纯 base64）→ 生成本地 HTML
        byte[] imageBytes = decodeImageBytes(content);
        if (imageBytes == null || imageBytes.length == 0) {
            System.out.println("[WARN] 二维码数据无法解析");
            return;
        }
        String base64Img = "data:image/png;base64," + Base64.getEncoder().encodeToString(imageBytes);
        try {
            File htmlFile = new File(fileName == null || fileName.isBlank() ? "qrcode.html" : fileName);
            String pageTitle = title == null || title.isBlank() ? "微信扫码登录" : title;
            String html = "<!DOCTYPE html><html><head><meta charset=\"UTF-8\"><title>" + pageTitle + "</title>"
                + "<style>body{text-align:center;padding:40px;font-family:sans-serif;background:#f5f5f5;}"
                + "h2{color:#07c160;}img{width:300px;height:300px;border:1px solid #ddd;padding:10px;background:white;}"
                + "</style></head><body><h2>" + pageTitle + "</h2>"
                + "<img src=\"" + base64Img + "\"/>"
                + "<p>用微信扫一扫确认登录</p></body></html>";
            java.nio.file.Files.write(htmlFile.toPath(), html.getBytes(StandardCharsets.UTF_8));
            Desktop.getDesktop().open(htmlFile);
            System.out.println("\uD83C\uDF10 已在浏览器打开扫码页面");
        } catch (Exception e) {
            System.err.println("[ERROR] 二维码显示失败: " + e.getMessage());
        }
        System.out.println();
    }

    private static void openInBrowser(String url) {
        if (Desktop.isDesktopSupported()) {
            try {
                Desktop.getDesktop().browse(new URI(url));
                System.out.println("\uD83C\uDF10 已在浏览器打开，请用微信扫码或在页面中确认登录");
            } catch (Exception e) {
                System.out.println("打开失败，请手动访问上面的链接");
            }
        } else {
            System.out.println("请手动在浏览器中打开上面的链接");
        }
    }

    private static byte[] decodeImageBytes(String content) {
        if (content == null) return null;
        String c = content.trim();
        if (c.isEmpty()) return null;

        // data:image/...;base64,xxxx
        if (c.toLowerCase().startsWith("data:image")) {
            int comma = c.indexOf(',');
            if (comma > 0) c = c.substring(comma + 1).trim();
            c = c.replaceAll("\\s+", "");
            try { return Base64.getDecoder().decode(c); } catch (Exception e) { return null; }
        }

        // http URL → 下载
        if (c.toLowerCase().startsWith("http://") || c.toLowerCase().startsWith("https://")) {
            try {
                HttpClient http = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(15))
                    .followRedirects(HttpClient.Redirect.ALWAYS)
                    .build();
                HttpRequest req = HttpRequest.newBuilder().uri(URI.create(c)).GET().build();
                HttpResponse<byte[]> resp = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
                if (resp.statusCode() == 200) return resp.body();
            } catch (Exception e) {
                return null;
            }
            return null;
        }

        // 纯 base64
        try {
            String clean = c.replaceAll("\\s+", "");
            return Base64.getDecoder().decode(clean);
        } catch (Exception e) {
            return null;
        }
    }
}
