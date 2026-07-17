package WechatAI.config;

import com.github.wechat.ilink.sdk.ILinkClient;
import com.github.wechat.ilink.sdk.core.config.ILinkConfig;
import com.github.wechat.ilink.sdk.core.listener.OnLoginListener;
import com.github.wechat.ilink.sdk.core.login.LoginContext;

public class WechatClientFactory {

    private WechatClientFactory() {
    }

    public static ILinkClient create() {
        return ILinkClient.builder()
                .config(ILinkConfig.builder()
                        .connectTimeoutMs(35000)
                        .readTimeoutMs(35000)
                        .httpMaxRetries(3)
                        .heartbeatEnabled(true)
                        .build())
                .onLogin(new OnLoginListener() {
                    @Override
                    public void onLoginSuccess(LoginContext context) {
                        System.out.println("✅ 恭喜你，登录成功！Bot ID: " + context.getBotId());
                    }

                    @Override
                    public void onLoginFailure(Throwable throwable) {
                        System.err.println("❌ 登录失败: " + throwable.getMessage());
                    }
                })
                .build();
    }
}
