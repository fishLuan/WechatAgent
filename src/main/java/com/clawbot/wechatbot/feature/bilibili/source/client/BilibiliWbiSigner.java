package com.clawbot.wechatbot.feature.bilibili.source.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/** 为 B 站 WBI Web API 生成动态签名。 */
@Component
public class BilibiliWbiSigner {
    private static final String NAV = "https://api.bilibili.com/x/web-interface/nav";
    private static final int[] MIXIN_KEY_ENC_TAB = {
        46, 47, 18, 2, 53, 8, 23, 32, 15, 50, 10, 31, 58, 3, 45, 35,
        27, 43, 5, 49, 33, 9, 42, 19, 29, 28, 14, 39, 12, 38, 41, 13,
        37, 48, 7, 16, 24, 55, 40, 61, 26, 17, 0, 1, 60, 51, 30, 4,
        22, 25, 54, 21, 56, 59, 6, 63, 57, 62, 11, 36, 20, 34, 44, 52
    };
    private static final long KEY_TTL_MILLIS = Duration.ofHours(6).toMillis();

    private final BilibiliHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private volatile String mixinKey;
    private volatile long mixinKeyExpiresAt;

    public BilibiliWbiSigner(BilibiliHttpClient httpClient, ObjectMapper objectMapper) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
    }

    public String sign(String baseUrl, Map<String, String> parameters) throws Exception {
        List<Map.Entry<String, String>> values = new ArrayList<>(parameters.entrySet());
        values.add(Map.entry("wts", Long.toString(System.currentTimeMillis() / 1000)));
        values.sort(Comparator.comparing(Map.Entry::getKey));
        StringBuilder query = new StringBuilder();
        for (Map.Entry<String, String> entry : values) {
            if (!query.isEmpty()) query.append('&');
            query.append(encode(entry.getKey())).append('=')
                .append(encode(sanitize(entry.getValue())));
        }
        String wRid = md5Hex(query + currentMixinKey());
        return baseUrl + "?" + query + "&w_rid=" + wRid;
    }

    private synchronized String currentMixinKey() throws Exception {
        if (mixinKey != null && System.currentTimeMillis() < mixinKeyExpiresAt) {
            return mixinKey;
        }
        JsonNode root = objectMapper.readTree(httpClient.getText(NAV));
        String imgKey = fileStem(root.at("/data/wbi_img/img_url").asText(""));
        String subKey = fileStem(root.at("/data/wbi_img/sub_url").asText(""));
        String source = imgKey + subKey;
        if (source.length() < 64) {
            throw new IllegalStateException("B站 WBI 动态密钥不可用");
        }
        StringBuilder mixed = new StringBuilder();
        for (int index : MIXIN_KEY_ENC_TAB) mixed.append(source.charAt(index));
        mixinKey = mixed.substring(0, 32);
        mixinKeyExpiresAt = System.currentTimeMillis() + KEY_TTL_MILLIS;
        return mixinKey;
    }

    private String fileStem(String url) {
        int slash = url.lastIndexOf('/');
        int dot = url.lastIndexOf('.');
        return slash >= 0 && dot > slash ? url.substring(slash + 1, dot) : "";
    }

    private String sanitize(String value) {
        return value == null ? "" : value.replaceAll("[!'()*]", "");
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private String md5Hex(String value) throws Exception {
        byte[] digest = MessageDigest.getInstance("MD5")
            .digest(value.getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder(32);
        for (byte item : digest) hex.append(String.format("%02x", item & 0xff));
        return hex.toString();
    }
}
