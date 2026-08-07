# ClawBot 答辩演示彩排清单

按「推荐 → 反馈 → 订阅 → 推送」走通，并核对口播数字（来源：`application.properties`，已于制作时校对）。

## 一、环境准备

- [ ] `BAILIAN_API_KEY` 已配置
- [ ] MongoDB 可用
- [ ] 微信扫码登录成功，Bot 在线
- [ ] 浏览器打开 `http://localhost:8080/console`

## 二、Demo 脚本（微信）

| 步 | 发送 | 期望 |
|----|------|------|
| 1 | `动漫推荐` | 编号列表 + 评分/题材/理由 |
| 2 | `订阅1`（或 `想看2`） | 成功文案 + 学习标签提示 |
| 3 | `显示推荐设置` | 可见偏好/标签变化 |
| 4 | `每天晚上十点推送3部高分电影` | 创建 `BILIBILI_PUSH` 任务 |
| 5 | 控制台 → 定时任务 | 表中出现对应任务 |
| 6 | （可选）等到点或手动触发 | 收到 `【B站推送】…` |

**能力边界（评委追问）**

- 即时操作：`bilibili` Skill / `bilibili_manage`
- 定时推送：`scheduler_manage` + `BILIBILI_PUSH`（不要用 bilibili Skill）

## 三、口播数字校对表

| 口播项 | 正确值 | 配置键 |
|--------|--------|--------|
| Agent 并行度 | 3 | `agent.max-parallelism` |
| 消息调度并行度 | 4 | `wechat.dispatch.parallelism` |
| 总执行超时 | 90s | `agent.guard.execution-timeout-seconds` |
| 重规划超时 | 8s | `agent.replan.timeout-seconds` |
| 重规划次数 | 1 | `agent.replan.max-count` |
| 内环 tool 轮次 | 5 | `deepseek.max-tool-rounds` |
| 每轮 tool 上限 | 4 | `agent.guard.max-tool-calls-per-round` |
| 总 tool 上限 | 8 | `agent.guard.max-total-tool-calls` |
| 同工具失败上限 | 2 | `agent.guard.max-same-tool-failures` |
| 单工具结果截断 | 8000 | `agent.guard.max-tool-result-chars` |
| 工具结果总量 | 16000 | `agent.guard.max-total-tool-result-chars` |
| 文档截断 | 8000 | `agent.input.max-document-chars` |
| 网页正文截断 | 6000 | `webpage.extract.max-body-chars` |
| 记忆近期轮次 | 15 | `clawbot.memory.recent-turns` |
| 摘要周期 | 10 | `clawbot.memory.summary-every` |
| B站搜索缓存 | 30 min | `clawbot.bilibili.search-cache-minutes` |
| B站请求间隔 | 2500 ms | `clawbot.bilibili.min-request-gap-millis` |
| 长文分片阈值 | 1500 | `wechat.reply.long-text-threshold` |
| 推荐打分 | 评分35% 热度5% 题材20% 标签40% | RecommendationCandidateScorer |
| 标签学习权重 | 订阅3 / 想看2 / 已看1 | BilibiliCatalogCommandService |
| Skill 数量 | 5 | skills/*/skill.yaml |
| Tool 约数 | 14 | FunctionTool 实现类 |

## 四、PPT 页序口播时长建议（共约 9 分钟）

| 页 | 内容 | 建议 |
|----|------|------|
| 1 | 封面 | 15s |
| 2 | 背景 | 60s |
| 3–5 | 前端 | 90s |
| 6–8 | B站演示 | 150s |
| 9–10 | Skill/Tool | 90s |
| 11–12 | 架构 | 90s |
| 13–14 | 性能 | 90s |
| 15 | 总结 Q&A | 30s + 提问 |

## 五、素材位置

- PPT：`docs/ppt/ClawBot-项目答辩展示.pptx`
- 截图：`docs/ppt/assets/*.png`
- Mockup（可再截）：`docs/ppt/mockups/`
- 重新生成：`cd docs/ppt && node generate-pptx.js`
