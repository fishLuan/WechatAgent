package com.clawbot.wechatbot.tools.UrlSafetyCheckerTool;

import com.clawbot.wechatbot.tools.FunctionTool;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

public class UrlSafetyChecker implements FunctionTool {

    private static final List<String> SUSPICIOUS_KEYWORDS = Arrays.asList(
        "中奖", "免费领取", "领取福利", "红包", "薅羊毛",
        "赌博", "博彩", "色情", "成人", "贷款", "高利贷", "信用贷",
        "诈骗", "刷单", "兼职日结", "高薪招聘", "内部通道", "独家内幕",
        "win", "free", "bonus", "gift", "luck", "prize", "lucky",
        "jackpot", "lottery", "casino", "porn", "xxx", "loan",
        "fastcash", "moneymaker", "getrich", "clickhere", "click-me",
        "login", "signin", "verify", "确认账号"
    );

    private static final List<String> SHORT_LINK_DOMAINS = Arrays.asList(
        "bit.ly", "t.cn", "url.cn", "tinyurl.com", "goo.gl",
        "is.gd", "cli.gs", "u.nu", "1url.com", "dwz.cn",
        "suo.im", "mrw.so", "tb.cn", "jd.cn.hn",
        "t.sina.com.cn", "url.ie", "ow.ly", "buff.ly",
        "adf.ly", "cutt.ly", "tiny.cc", "lnkd.in"
    );

    private static final Pattern IP_PATTERN = Pattern.compile(
        "^(\\d{1,3}\\.){3}\\d{1,3}$"
    );

    private static final List<String> SUSPICIOUS_PARAMS = Arrays.asList(
        "redirect", "redirect_url", "return_url", "return_to",
        "url", "forward", "next", "dest", "destination",
        "login", "signin", "auth", "authenticate",
        "verify", "verification", "confirm",
        "callback", "continue"
    );

    private final ObjectMapper mapper;

    public UrlSafetyChecker() {
        this(new ObjectMapper());
    }

    public UrlSafetyChecker(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public String name() {
        return "check_url_safety";
    }

    @Override
    public JsonNode definition() {
        ObjectNode function = mapper.createObjectNode();
        function.put("name", name());
        function.put("description",
            "检测用户分享的链接是否存在安全风险。支持协议检测、域名可疑关键字、端口异常、IP直连、短链识别，以及可选的短链展开（最多追踪 5 级重定向）。"
        );

        ObjectNode parameters = function.putObject("parameters");
        parameters.put("type", "object");
        ObjectNode properties = parameters.putObject("properties");

        properties.putObject("url")
            .put("type", "string")
            .put("description", "需要检测的链接，必须包含协议（http:// 或 https://），例如：https://www.example.com/path?query=1");

        properties.putObject("expand_short_link")
            .put("type", "boolean")
            .put("description",
                "是否尝试展开短链（bit.ly / t.cn 等）并检测最终目的地；默认 false。展开会额外产生网络请求，最多追踪 5 级重定向。"
            );

        parameters.putArray("required").add("url");

        ObjectNode tool = mapper.createObjectNode();
        tool.put("type", "function");
        tool.set("function", function);
        return tool;
    }

    @Override
    public String execute(JsonNode arguments) throws Exception {
        String url = arguments == null ? "" : arguments.path("url").asText("").trim();
        boolean expandShortLink = arguments != null
            && arguments.path("expand_short_link").asBoolean(false);
        if (url.isEmpty()) return error("url 参数不能为空");

        URL parsedUrl;
        try {
            parsedUrl = new URL(url);
        } catch (Exception e) {
            return error("无法解析链接，请检查格式：" + e.getMessage());
        }

        List<String> issues = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        List<String> positives = new ArrayList<>();

        String protocol = parsedUrl.getProtocol().toLowerCase();
        String host = parsedUrl.getHost() == null ? "" : parsedUrl.getHost().toLowerCase();
        int port = parsedUrl.getPort();
        int effectivePort = port == -1 ? defaultPortFor(protocol) : port;
        String query = parsedUrl.getQuery();
        String path = parsedUrl.getPath();

        if (!"http".equals(protocol) && !"https".equals(protocol)) {
            issues.add("协议为 " + protocol + "（非 http/https，可能是 ftp/file 等非常规协议）");
        } else if ("http".equals(protocol)) {
            warnings.add("协议为 HTTP（非加密传输），不要在该页面输入敏感信息（账号密码、身份证、支付信息等）");
        } else {
            positives.add("协议为 HTTPS（加密传输）");
        }

        if (host.isEmpty()) {
            issues.add("链接缺少域名");
        } else {
            if (IP_PATTERN.matcher(host).matches()) {
                warnings.add("域名是 IP 地址直连，官方服务通常使用域名而非 IP，需要额外留意内容来源");
            }

            if (SHORT_LINK_DOMAINS.stream().anyMatch(host::endsWith)) {
                warnings.add("检测到短链服务域名（" + host + "），真实地址被隐藏，建议谨慎点击");
            }

            for (String kw : SUSPICIOUS_KEYWORDS) {
                if (host.contains(kw.toLowerCase())) {
                    issues.add("域名包含可疑关键字：" + kw);
                    break;
                }
            }

            if (path != null && !path.isEmpty()) {
                for (String kw : SUSPICIOUS_KEYWORDS) {
                    if (path.toLowerCase().contains(kw.toLowerCase())) {
                        warnings.add("路径中包含可疑关键字：" + kw);
                        break;
                    }
                }
            }
        }

        if (port != -1 && port != 80 && port != 443) {
            warnings.add("使用非标准端口：" + port + "（常规 http=80，https=443）");
        }

        if (query != null && !query.isEmpty()) {
            String lowerQuery = query.toLowerCase();
            for (String p : SUSPICIOUS_PARAMS) {
                if (lowerQuery.contains(p.toLowerCase())) {
                    warnings.add("参数中包含跳转相关关键字：" + p + "（可能用于钓鱼链中跳转到伪造登录页）");
                    break;
                }
            }
        }

        String finalUrl = null;
        int redirectHops = 0;
        if (expandShortLink && SHORT_LINK_DOMAINS.stream().anyMatch(host::endsWith)) {
            try {
                ExpandResult r = expandShortLink(parsedUrl.toString(), 5);
                finalUrl = r.finalUrl;
                redirectHops = r.hops;
                if (finalUrl != null && !finalUrl.equalsIgnoreCase(url)) {
                    try {
                        URL f = new URL(finalUrl);
                        String finalHost = f.getHost() == null ? "" : f.getHost().toLowerCase();
                        if (IP_PATTERN.matcher(finalHost).matches()) {
                            issues.add("短链展开后的地址是 IP 直连：" + finalHost);
                        }
                        for (String kw : SUSPICIOUS_KEYWORDS) {
                            if (finalHost.contains(kw.toLowerCase())) {
                                issues.add("短链展开后的域名包含可疑关键字：" + kw);
                                break;
                            }
                        }
                    } catch (Exception ignored) {
                        warnings.add("短链展开后的地址无法再次解析：" + finalUrl);
                    }
                }
            } catch (Exception e) {
                warnings.add("短链展开失败：" + e.getMessage());
            }
        }

        int riskScore = issues.size() * 20 + Math.min(warnings.size() * 8, 40);
        String riskLevel;
        if (riskScore >= 60) riskLevel = "高风险";
        else if (riskScore >= 30) riskLevel = "中等风险";
        else if (riskScore > 0) riskLevel = "低风险";
        else riskLevel = "安全";

        ObjectNode result = mapper.createObjectNode();
        result.put("success", true);
        result.put("url", url);
        result.put("protocol", protocol);
        result.put("host", host);
        result.put("port", effectivePort);
        result.put("path", path == null ? "" : path);
        result.put("risk_level", riskLevel);
        result.put("risk_score", riskScore);

        ArrayNode issuesNode = result.putArray("issues");
        issues.forEach(issuesNode::add);

        ArrayNode warningsNode = result.putArray("warnings");
        warnings.forEach(warningsNode::add);

        ArrayNode positivesNode = result.putArray("positives");
        positives.forEach(positivesNode::add);

        if (finalUrl != null) {
            result.put("expanded_url", finalUrl);
            result.put("redirect_hops", redirectHops);
        }

        result.put("advice", buildAdvice(riskLevel, issues.size(), warnings.size()));
        return mapper.writeValueAsString(result);
    }

    private String error(String message) throws Exception {
        ObjectNode result = mapper.createObjectNode();
        result.put("success", false);
        result.put("error", message);
        return mapper.writeValueAsString(result);
    }

    private static int defaultPortFor(String protocol) {
        if ("https".equals(protocol)) return 443;
        if ("http".equals(protocol)) return 80;
        if ("ftp".equals(protocol)) return 21;
        return -1;
    }

    private static String buildAdvice(String riskLevel, int issueCount, int warningCount) {
        if ("高风险".equals(riskLevel)) {
            return "建议：不要点击该链接，不要在该页面输入任何账号密码或支付信息；"
                + "如果来源可疑，请直接忽略。检测到 " + issueCount + " 项高危问题与 "
                + warningCount + " 项可疑特征。";
        }
        if ("中等风险".equals(riskLevel)) {
            return "建议：谨慎点击，不要在该页面输入敏感信息；如果涉及登录、支付，建议"
                + "手动到官方网站输入，不要使用链接。检测到 " + warningCount + " 项可疑特征。";
        }
        if ("低风险".equals(riskLevel)) {
            return "建议：可以正常访问，但仍需留意页面内容是否与描述一致，不要在非常规端口"
                + "页面输入敏感信息。";
        }
        return "当前链接未检测到明显风险特征，可正常访问。";
    }

    private static ExpandResult expandShortLink(String startUrl, int maxHops) throws Exception {
        String current = startUrl;
        int hops = 0;
        while (hops < maxHops) {
            HttpURLConnection conn = (HttpURLConnection) new URL(current).openConnection();
            conn.setRequestMethod("HEAD");
            conn.setInstanceFollowRedirects(false);
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            int code = conn.getResponseCode();
            if (code >= 300 && code < 400) {
                String location = conn.getHeaderField("Location");
                if (location == null || location.isEmpty()) break;
                current = location.startsWith("http") ? location
                    : new URL(new URL(current), location).toString();
                hops++;
                conn.disconnect();
            } else {
                conn.disconnect();
                break;
            }
        }
        return new ExpandResult(current, hops);
    }

    private static final class ExpandResult {
        final String finalUrl;
        final int hops;

        ExpandResult(String finalUrl, int hops) {
            this.finalUrl = finalUrl;
            this.hops = hops;
        }
    }
}
