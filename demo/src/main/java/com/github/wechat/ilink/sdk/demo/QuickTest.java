package com.github.wechat.ilink.sdk.demo;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;

public class QuickTest {
    public static void main(String[] args) throws Exception {
        System.out.println("=== 测试微信 API ===");

        String url = "https://ilinkai.weixin.qq.com/ilink/bot/get_bot_qrcode?bot_type=3";
        System.out.println("请求: " + url);

        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod("GET");
        conn.setRequestProperty("User-Agent", "Mozilla/5.0");
        conn.setConnectTimeout(30000);
        conn.setReadTimeout(30000);

        int code = conn.getResponseCode();
        System.out.println("HTTP状态: " + code);

        BufferedReader reader = new BufferedReader(
                new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            sb.append(line);
        }
        reader.close();

        String response = sb.toString();
        System.out.println("\n=== 原始响应 ===");
        System.out.println(response.length() > 500 ? response.substring(0, 500) + "..." : response);

        // 简单解析 JSON
        String qrcodeImg = extractJsonValue(response, "qrcode_img_content");
        String qrcode = extractJsonValue(response, "qrcode");

        System.out.println("\n=== 解析结果 ===");
        System.out.println("qrcode (ID): " + (qrcode == null ? "null" : qrcode.substring(0, Math.min(50, qrcode.length())) + "..."));
        System.out.println("qrcode_img_content 长度: " + (qrcodeImg == null ? "null" : qrcodeImg.length()));
        System.out.println("qrcode_img_content 开头: " + (qrcodeImg == null ? "null" : qrcodeImg.substring(0, Math.min(100, qrcodeImg.length()))));

        if (qrcodeImg != null && qrcodeImg.length() > 100) {
            // 保存为 HTML
            String imgSrc = qrcodeImg;
            if (!imgSrc.startsWith("data:image")) {
                imgSrc = "data:image/png;base64," + imgSrc;
            }

            String html = "<!DOCTYPE html><html><head><meta charset=\"UTF-8\"><title>微信登录</title></head>" +
                    "<body style=\"text-align:center;padding:40px;background:#f5f5f5;font-family:Arial;\">" +
                    "<div style=\"max-width:500px;margin:50px auto;background:white;padding:40px;border-radius:10px;\">" +
                    "<h1 style=\"color:#07c160;\">微信扫码登录</h1>" +
                    "<div style=\"margin:30px 0;\"><img src=\"" + imgSrc + "\" style=\"width:300px;height:300px;border:4px solid #07c160;border-radius:10px;padding:10px;\" /></div>" +
                    "<p style=\"color:#666;font-size:16px;\">请使用微信扫描上方二维码</p>" +
                    "<p style=\"color:#999;font-size:14px;margin-top:30px;\">二维码有效期约 3 分钟</p>" +
                    "</div></body></html>";

            java.nio.file.Files.write(java.nio.file.Paths.get("qrcode.html"), html.getBytes(StandardCharsets.UTF_8));
            System.out.println("\n[成功] 二维码已保存到 qrcode.html");

            // 打开浏览器
            try {
                java.awt.Desktop.getDesktop().browse(new java.io.File("qrcode.html").toURI());
                System.out.println("[成功] 已在浏览器中打开");
            } catch (Exception e) {
                System.out.println("[提示] 请手动打开: qrcode.html");
            }
        } else {
            System.out.println("\n[错误] 无法获取有效二维码内容");
        }

        System.out.println("\n测试完成！");
    }

    private static String extractJsonValue(String json, String key) {
        String searchKey = "\"" + key + "\"";
        int idx = json.indexOf(searchKey);
        if (idx < 0) return null;

        int colonIdx = json.indexOf(":", idx);
        if (colonIdx < 0) return null;

        int quoteStart = json.indexOf("\"", colonIdx + 1);
        if (quoteStart < 0) return null;

        int quoteEnd = json.indexOf("\"", quoteStart + 1);
        while (quoteEnd > 0 && json.charAt(quoteEnd - 1) == '\\') {
            quoteEnd = json.indexOf("\"", quoteEnd + 1);
        }

        if (quoteEnd < 0) return null;

        String value = json.substring(quoteStart + 1, quoteEnd);
        // 移除转义字符
        return value.replace("\\/", "/").replace("\\n", "").replace("\\r", "");
    }
}