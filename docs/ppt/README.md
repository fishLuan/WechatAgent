# ClawBot 答辩 PPT 交付物

## 成品

- **[ClawBot-项目答辩展示.pptx](./ClawBot-项目答辩展示.pptx)** — 15 页，含口播备注（Notes）
- **[DEMO-REHEARSAL.md](./DEMO-REHEARSAL.md)** — 演示脚本与配置数字校对表

## 素材

| 路径 | 说明 |
|------|------|
| `assets/console-*.png` | 控制台四屏示意截图 |
| `assets/wechat-demo.png` | 微信 B站闭环对话示意 |
| `assets/architecture-*.png` | 主链路 / 双环架构图 |
| `mockups/*.html` | 高保真 HTML（可本地再截或换真实截图） |

## 重新生成

```bash
cd docs/ppt
node serve.js          # 另开终端：静态资源 http://127.0.0.1:8765
node capture-screens.js
node generate-pptx.js
```

正式答辩建议：启动真实 Bot 后，用 `/console` 与微信真机截图替换 `assets/` 中对应 PNG，再执行 `node generate-pptx.js`。
