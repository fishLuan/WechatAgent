package com.luanxv.pre.wechatAI.config;

import com.github.wechat.ilink.sdk.ILinkClient;
import com.github.wechat.ilink.sdk.core.config.ILinkConfig;
import com.github.wechat.ilink.sdk.core.listener.OnLoginListener;
import com.github.wechat.ilink.sdk.core.login.LoginContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class WechatClientConfiguration {
    @Bean
    public ILinkClient iLinkClient() {
        return ILinkClient.builder()
                .config(ILinkConfig.builder()
                        .connectTimeoutMs(35_000)
                        .readTimeoutMs(35_000)
                        .httpMaxRetries(3)
                        .heartbeatEnabled(true)
                        .build())
                .onLogin(new OnLoginListener() {
                    @Override
                    public void onLoginSuccess(LoginContext context) {
                        System.out.println("微信登录成功，botId = " + context.getBotId());
                    }

                    @Override
                    public void onLoginFailure(Throwable throwable) {
                        System.err.println("微信登录失败: " + throwable.getMessage());
                    }
                })
                .build();
    }
}
