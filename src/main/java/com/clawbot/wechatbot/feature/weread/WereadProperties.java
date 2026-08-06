package com.clawbot.wechatbot.feature.weread;

import org.springframework.stereotype.Component;

/**
 * 微信读书 Agent Skill 配置。
 *
 * <p>API Key 从环境变量 {@code WEREAD_API_KEY} 读取（个人账号数据凭证，
 * 部署时由部署者配置，不入代码不入库）。Key 未配置时 skill 返回明确提示，
 * 不影响应用启动与其他功能。</p>
 */
@Component
public class WereadProperties {
    private final String apiKey;

    public WereadProperties() {
        this.apiKey = System.getenv("WEREAD_API_KEY");
    }

    public boolean hasApiKey() {
        return apiKey != null && !apiKey.isBlank();
    }

    /** 返回 Key（仅供网关客户端使用，勿打印或持久化）。 */
    public String getApiKey() {
        return apiKey;
    }
}
