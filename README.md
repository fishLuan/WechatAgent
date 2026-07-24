# ClawBot WeChat Agent

## 钉钉登录与异常通知

在钉钉群中添加自定义机器人，推荐开启“加签”安全设置，然后配置以下环境变量：

```text
DINGTALK_ENABLED=true
DINGTALK_WEBHOOK=https://oapi.dingtalk.com/robot/send?access_token=你的访问令牌
DINGTALK_SECRET=SEC你的加签密钥
```

重启应用后会推送：

- 微信登录地址生成通知（SDK 返回 HTTP/HTTPS 地址时可在手机端点击）
- 微信登录成功或失败通知
- 微信消息轮询、消息处理及程序未捕获异常通知

通知发送失败不会中断微信机器人；相同错误默认在 60 秒内只通知一次。Webhook
和加签密钥属于敏感配置，不要直接写入 `application.properties` 或提交到 Git。
