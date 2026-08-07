/**
 * Generate ClawBot defense/demo PPT (15 slides).
 * Run: node generate-pptx.js
 */
const PptxGenJS = require("pptxgenjs");
const path = require("path");

const assets = path.join(__dirname, "assets");
const outFile = path.join(__dirname, "ClawBot-项目答辩展示.pptx");

const COLORS = {
  bg: "0F172A",
  card: "1E293B",
  accent: "3B82F6",
  green: "10B981",
  amber: "F59E0B",
  text: "F8FAFC",
  muted: "94A3B8",
  white: "FFFFFF",
  soft: "E2E8F0",
};

function addNotes(slide, text) {
  slide.addNotes(text);
}

async function main() {
  const pptx = new PptxGenJS();
  pptx.defineLayout({ name: "WIDE", width: 13.333, height: 7.5 });
  pptx.layout = "WIDE";
  pptx.author = "ClawBot Team";
  pptx.title = "ClawBot 项目答辩/展示";
  pptx.subject = "前端 · B站 · Skill/Tool · 架构 · 性能优化";

  // ---------- 1 Cover ----------
  {
    const s = pptx.addSlide();
    s.addShape(pptx.shapes.RECTANGLE, { x: 0, y: 0, w: 13.333, h: 7.5, fill: { color: COLORS.bg } });
    s.addShape(pptx.shapes.RECTANGLE, { x: 0, y: 0, w: 0.18, h: 7.5, fill: { color: COLORS.accent } });
    s.addText("ClawBot", {
      x: 0.8, y: 2.2, w: 11, h: 0.8,
      fontSize: 48, fontFace: "Microsoft YaHei", bold: true, color: COLORS.text,
    });
    s.addText("面向影视动漫爱好者的微信 AI Agent", {
      x: 0.8, y: 3.1, w: 11, h: 0.5,
      fontSize: 24, fontFace: "Microsoft YaHei", color: COLORS.soft,
    });
    s.addText("答辩 / 展示 PPT  ·  前端控制台 · B站闭环 · Skill/Tool · 架构 · 性能优化", {
      x: 0.8, y: 4.0, w: 11, h: 0.4,
      fontSize: 14, fontFace: "Microsoft YaHei", color: COLORS.muted,
    });
    s.addText("建议口播 8–10 分钟  ·  15 页", {
      x: 0.8, y: 6.6, w: 11, h: 0.3,
      fontSize: 12, fontFace: "Microsoft YaHei", color: COLORS.muted,
    });
    addNotes(s, "开场：介绍 ClawBot 是微信侧 AI Agent，控制台用于运维与答辩演示。接下来按痛点→方案→演示→能力→架构→性能讲述。");
  }

  // ---------- 2 Background ----------
  {
    const s = pptx.addSlide();
    s.addShape(pptx.shapes.RECTANGLE, { x: 0, y: 0, w: 13.333, h: 7.5, fill: { color: COLORS.bg } });
    s.addText("项目背景", {
      x: 0.6, y: 0.35, w: 12, h: 0.5,
      fontSize: 28, fontFace: "Microsoft YaHei", bold: true, color: COLORS.text,
    });
    s.addShape(pptx.shapes.ROUNDED_RECTANGLE, {
      x: 0.6, y: 1.1, w: 12.1, h: 3.2, fill: { color: COLORS.card }, rectRadius: 0.1,
    });
    s.addText(
      "随着影视动漫资源爆炸增长，用户选片困难。现有平台多以流量优先，难以贴合个人喜好；追番追剧需跨平台手动查看更新，订阅管理零散，容易错过更新。\n\n本 AI Agent 面向影视动漫爱好者，依托用户自定义兴趣标签实现动漫、影视剧集个性化推荐，支持每日定时推送、订阅作品更新提醒，统一完成内容筛选与消息通知，简化找片与追更流程。",
      {
        x: 0.9, y: 1.3, w: 11.5, h: 2.8,
        fontSize: 16, fontFace: "Microsoft YaHei", color: COLORS.soft, valign: "middle",
      }
    );
    const pains = [
      { t: "选片难", s: "→ 标签推荐" },
      { t: "喜好难贴合", s: "→ 定时推送" },
      { t: "追更零散", s: "→ 订阅提醒" },
    ];
    pains.forEach((p, i) => {
      const x = 0.6 + i * 4.1;
      s.addShape(pptx.shapes.ROUNDED_RECTANGLE, {
        x, y: 4.7, w: 3.8, h: 1.8, fill: { color: COLORS.card }, rectRadius: 0.1,
      });
      s.addText(p.t, {
        x, y: 5.0, w: 3.8, h: 0.5,
        fontSize: 18, fontFace: "Microsoft YaHei", bold: true, color: COLORS.accent, align: "center",
      });
      s.addText(p.s, {
        x, y: 5.6, w: 3.8, h: 0.5,
        fontSize: 16, fontFace: "Microsoft YaHei", color: COLORS.green, align: "center",
      });
    });
    addNotes(s, "背景约110字可原样朗读。点出三个痛点标签，对应三项能力。强调交互在微信，不是又一个视频网站。");
  }

  // ---------- 3 Console Dash ----------
  {
    const s = pptx.addSlide();
    s.addShape(pptx.shapes.RECTANGLE, { x: 0, y: 0, w: 13.333, h: 7.5, fill: { color: COLORS.bg } });
    s.addText("前端展示 · 仪表盘", {
      x: 0.5, y: 0.25, w: 8, h: 0.45,
      fontSize: 24, fontFace: "Microsoft YaHei", bold: true, color: COLORS.text,
    });
    s.addText("http://localhost:8080/console  ·  运维 / 答辩控制台", {
      x: 0.5, y: 0.7, w: 8, h: 0.3,
      fontSize: 12, fontFace: "Microsoft YaHei", color: COLORS.muted,
    });
    s.addImage({ path: path.join(assets, "console-dash.png"), x: 0.4, y: 1.1, w: 8.6, h: 5.9 });
    s.addShape(pptx.shapes.ROUNDED_RECTANGLE, {
      x: 9.2, y: 1.3, w: 3.7, h: 5.4, fill: { color: COLORS.card }, rectRadius: 0.1,
    });
    s.addText("要点", {
      x: 9.4, y: 1.5, w: 3.3, h: 0.4,
      fontSize: 16, fontFace: "Microsoft YaHei", bold: true, color: COLORS.accent,
    });
    s.addText(
      [
        { text: "工具 14 / Skill 5\n", options: { breakLine: false } },
        { text: "Bot 在线状态一目了然\n\n", options: { breakLine: false } },
        { text: "真实交互在微信\n", options: { breakLine: false } },
        { text: "控制台用于演示与运维\n\n", options: { breakLine: false } },
        { text: "并行度 / 超时等\n运行参数可见", options: { breakLine: false } },
      ],
      { x: 9.4, y: 2.1, w: 3.3, h: 4.2, fontSize: 14, fontFace: "Microsoft YaHei", color: COLORS.soft }
    );
    addNotes(s, "口播：前端不是 C 端产品页，而是运维与答辩演示控制台。展示工具数、Skill 数、运行中状态。");
  }

  // ---------- 4 Console Tools ----------
  {
    const s = pptx.addSlide();
    s.addShape(pptx.shapes.RECTANGLE, { x: 0, y: 0, w: 13.333, h: 7.5, fill: { color: COLORS.bg } });
    s.addText("前端展示 · 能力中心", {
      x: 0.5, y: 0.25, w: 12, h: 0.45,
      fontSize: 24, fontFace: "Microsoft YaHei", bold: true, color: COLORS.text,
    });
    s.addText("Skill / Tool 卡片网格 · 可在线试跑（示例：get_weather）", {
      x: 0.5, y: 0.7, w: 12, h: 0.3,
      fontSize: 12, fontFace: "Microsoft YaHei", color: COLORS.muted,
    });
    s.addImage({ path: path.join(assets, "console-tools.png"), x: 0.5, y: 1.15, w: 12.3, h: 5.9 });
    addNotes(s, "点开天气工具弹窗，展示即时执行结果，体现能力可观测、可试跑。");
  }

  // ---------- 5 Tasks + History ----------
  {
    const s = pptx.addSlide();
    s.addShape(pptx.shapes.RECTANGLE, { x: 0, y: 0, w: 13.333, h: 7.5, fill: { color: COLORS.bg } });
    s.addText("前端展示 · 定时任务 & 对话记录", {
      x: 0.5, y: 0.25, w: 12, h: 0.45,
      fontSize: 24, fontFace: "Microsoft YaHei", bold: true, color: COLORS.text,
    });
    s.addImage({ path: path.join(assets, "console-tasks.png"), x: 0.35, y: 0.9, w: 6.3, h: 3.9 });
    s.addImage({ path: path.join(assets, "console-history.png"), x: 6.8, y: 0.9, w: 6.2, h: 3.9 });
    s.addShape(pptx.shapes.ROUNDED_RECTANGLE, {
      x: 0.5, y: 5.1, w: 12.3, h: 1.9, fill: { color: COLORS.card }, rectRadius: 0.1,
    });
    s.addText(
      "左：BILIBILI_PUSH 任务可见（与微信「每天晚上十点推送…」同源）    右：Mongo 记忆 recent-turns=15 · summary-every=10\n口播：微信自然语言创建的任务，在控制台可 CRUD / 开关；对话记录证明多轮上下文与标签学习可追溯。",
      {
        x: 0.75, y: 5.35, w: 11.8, h: 1.4,
        fontSize: 14, fontFace: "Microsoft YaHei", color: COLORS.soft, valign: "middle",
      }
    );
    addNotes(s, "强调定时任务类型含 BILIBILI_PUSH；对话记录体现订阅后标签学习。");
  }

  // ---------- 6 Bilibili demo flow ----------
  {
    const s = pptx.addSlide();
    s.addShape(pptx.shapes.RECTANGLE, { x: 0, y: 0, w: 13.333, h: 7.5, fill: { color: COLORS.bg } });
    s.addText("B站功能演示 · 闭环脚本", {
      x: 0.5, y: 0.3, w: 12, h: 0.5,
      fontSize: 24, fontFace: "Microsoft YaHei", bold: true, color: COLORS.text,
    });
    const steps = [
      "动漫推荐",
      "想看/订阅编号",
      "标签自动学习",
      "再推荐更贴口味",
      "订阅连载",
      "更新提醒",
      "定时推送",
    ];
    steps.forEach((t, i) => {
      const x = 0.4 + (i % 7) * 1.85;
      s.addShape(pptx.shapes.ROUNDED_RECTANGLE, {
        x, y: 1.3, w: 1.7, h: 1.1, fill: { color: i === 0 ? COLORS.accent : COLORS.card }, rectRadius: 0.08,
      });
      s.addText(t, {
        x, y: 1.5, w: 1.7, h: 0.7,
        fontSize: 12, fontFace: "Microsoft YaHei", color: COLORS.text, align: "center", valign: "middle", bold: true,
      });
      if (i < steps.length - 1) {
        s.addText("→", {
          x: x + 1.55, y: 1.55, w: 0.35, h: 0.5,
          fontSize: 18, color: COLORS.muted, align: "center",
        });
      }
    });
    s.addImage({ path: path.join(assets, "wechat-demo.png"), x: 4.5, y: 2.7, w: 4.3, h: 4.5 });
    s.addShape(pptx.shapes.ROUNDED_RECTANGLE, {
      x: 0.4, y: 2.8, w: 3.8, h: 4.2, fill: { color: COLORS.card }, rectRadius: 0.1,
    });
    s.addText("演示话术", {
      x: 0.6, y: 3.0, w: 3.4, h: 0.4,
      fontSize: 16, fontFace: "Microsoft YaHei", bold: true, color: COLORS.accent,
    });
    s.addText(
      "1. 发送「动漫推荐」\n2. 回复「订阅1」\n3. 观察标签学习\n4. 「每天晚上十点\n    推送3部高分电影」\n5. 到点【B站推送】",
      {
        x: 0.6, y: 3.5, w: 3.4, h: 3.2,
        fontSize: 14, fontFace: "Microsoft YaHei", color: COLORS.soft,
      }
    );
    addNotes(s, "现场按推荐→订阅→定时推送一条龙演示。右侧为微信对话示意截图。");
  }

  // ---------- 7 Recommend + scoring ----------
  {
    const s = pptx.addSlide();
    s.addShape(pptx.shapes.RECTANGLE, { x: 0, y: 0, w: 13.333, h: 7.5, fill: { color: COLORS.bg } });
    s.addText("B站 · 个性化推荐与反馈闭环", {
      x: 0.5, y: 0.3, w: 12, h: 0.5,
      fontSize: 24, fontFace: "Microsoft YaHei", bold: true, color: COLORS.text,
    });
    const cards = [
      { title: "候选召回", body: "有标签：按题材/标签检索\n无标签：Mongo 高分池\n可回落远程 B站候选" },
      { title: "综合打分", body: "评分 35% + 热度 5%\n题材 20% + 标签 40%\n排除已看/不喜欢" },
      { title: "交互闭环", body: "PendingRecommendationStore\n支持「订阅2 / 想看3」\n反馈反哺兴趣标签" },
      { title: "自动学习", body: "订阅权重 3\n想看 2 · 已看 1\n不喜欢移除标签" },
    ];
    cards.forEach((c, i) => {
      const x = 0.45 + (i % 4) * 3.2;
      s.addShape(pptx.shapes.ROUNDED_RECTANGLE, {
        x, y: 1.2, w: 3.0, h: 3.6, fill: { color: COLORS.card }, rectRadius: 0.1,
      });
      s.addText(c.title, {
        x: x + 0.15, y: 1.45, w: 2.7, h: 0.5,
        fontSize: 18, fontFace: "Microsoft YaHei", bold: true, color: COLORS.accent,
      });
      s.addText(c.body, {
        x: x + 0.15, y: 2.2, w: 2.7, h: 2.3,
        fontSize: 14, fontFace: "Microsoft YaHei", color: COLORS.soft,
      });
    });
    s.addText("关键类：BilibiliRecommendationServiceImpl · RecommendationCandidateScorer · BilibiliPreference", {
      x: 0.5, y: 6.6, w: 12, h: 0.35,
      fontSize: 12, fontFace: "Consolas", color: COLORS.muted,
    });
    addNotes(s, "评委爱问打分权重：记住 35/5/20/40。订阅权重最高，用于兴趣进化。");
  }

  // ---------- 8 Subscribe + push boundary ----------
  {
    const s = pptx.addSlide();
    s.addShape(pptx.shapes.RECTANGLE, { x: 0, y: 0, w: 13.333, h: 7.5, fill: { color: COLORS.bg } });
    s.addText("B站 · 订阅追更 & 定时推送边界", {
      x: 0.5, y: 0.3, w: 12, h: 0.5,
      fontSize: 24, fontFace: "Microsoft YaHei", bold: true, color: COLORS.text,
    });
    s.addShape(pptx.shapes.ROUNDED_RECTANGLE, {
      x: 0.5, y: 1.2, w: 6.0, h: 5.4, fill: { color: COLORS.card }, rectRadius: 0.1,
    });
    s.addText("即时操作", {
      x: 0.8, y: 1.5, w: 5.4, h: 0.45,
      fontSize: 20, fontFace: "Microsoft YaHei", bold: true, color: COLORS.green,
    });
    s.addText(
      "入口：bilibili Skill / bilibili_manage\n\n能力：搜索 · 立即推荐 · 订阅 · 标记\n检查更新 · 偏好设置\n\n调度：BilibiliSubscriptionScheduler\n通知：UpdateNotification → 微信",
      {
        x: 0.8, y: 2.2, w: 5.4, h: 3.8,
        fontSize: 15, fontFace: "Microsoft YaHei", color: COLORS.soft,
      }
    );
    s.addShape(pptx.shapes.ROUNDED_RECTANGLE, {
      x: 6.9, y: 1.2, w: 6.0, h: 5.4, fill: { color: COLORS.card }, rectRadius: 0.1,
    });
    s.addText("定时推送", {
      x: 7.2, y: 1.5, w: 5.4, h: 0.45,
      fontSize: 20, fontFace: "Microsoft YaHei", bold: true, color: COLORS.amber,
    });
    s.addText(
      "入口：scheduler_manage\ntask_type = BILIBILI_PUSH\n\n示例：「每天晚上十点推送3部高分电影」\n\n执行：BilibiliPushContentProvider\n控制台「定时任务」可查看开关",
      {
        x: 7.2, y: 2.2, w: 5.4, h: 3.8,
        fontSize: 15, fontFace: "Microsoft YaHei", color: COLORS.soft,
      }
    );
    addNotes(s, "防追问金句：即时走 bilibili skill；定时走 scheduler_manage + BILIBILI_PUSH，不要混用。");
  }

  // ---------- 9 Skills ----------
  {
    const s = pptx.addSlide();
    s.addShape(pptx.shapes.RECTANGLE, { x: 0, y: 0, w: 13.333, h: 7.5, fill: { color: COLORS.bg } });
    s.addText("Skill 体系 · 领域能力包", {
      x: 0.5, y: 0.3, w: 12, h: 0.5,
      fontSize: 24, fontFace: "Microsoft YaHei", bold: true, color: COLORS.text,
    });
    s.addText("YAML 声明（触发词 / 超时 / 校验） + Java Executor · SkillManager 热加载", {
      x: 0.5, y: 0.85, w: 12, h: 0.35,
      fontSize: 13, fontFace: "Microsoft YaHei", color: COLORS.muted,
    });
    const skills = [
      ["bilibili", "B站搜索/推荐/订阅/标记"],
      ["excel-operation", "Excel 生成与筛选聚合"],
      ["weread", "微信读书书架/笔记/荐书"],
      ["voice-reply", "文字转语音（超时60s）"],
      ["document-generation", "生成 Word / PDF"],
    ];
    skills.forEach((row, i) => {
      const y = 1.4 + i * 0.95;
      s.addShape(pptx.shapes.ROUNDED_RECTANGLE, {
        x: 0.5, y, w: 12.3, h: 0.85, fill: { color: COLORS.card }, rectRadius: 0.08,
      });
      s.addText(row[0], {
        x: 0.8, y: y + 0.2, w: 3.5, h: 0.45,
        fontSize: 16, fontFace: "Consolas", bold: true, color: COLORS.amber,
      });
      s.addText(row[1], {
        x: 4.5, y: y + 0.2, w: 7.8, h: 0.45,
        fontSize: 16, fontFace: "Microsoft YaHei", color: COLORS.soft,
      });
    });
    addNotes(s, "Skill=领域能力包。bilibili.yaml 明确：定时推送不要用本技能，改用 scheduler_manage。");
  }

  // ---------- 10 Tools ----------
  {
    const s = pptx.addSlide();
    s.addShape(pptx.shapes.RECTANGLE, { x: 0, y: 0, w: 13.333, h: 7.5, fill: { color: COLORS.bg } });
    s.addText("Tool 矩阵 · Function-calling 原子能力", {
      x: 0.5, y: 0.25, w: 12, h: 0.45,
      fontSize: 24, fontFace: "Microsoft YaHei", bold: true, color: COLORS.text,
    });
    const groups = [
      { title: "生活查询", items: "get_weather\nget_route_plan\nget_route_weather\nget_current_time\nconvert_currency\nget_news\nweb_search" },
      { title: "内容安全", items: "extract_web_page\ncheck_url_safety" },
      { title: "趣味工具", items: "calculate_bazi_fortune\ncalculate_zodiac_info\nvalidate_id_card" },
      { title: "系统能力", items: "scheduler_manage\nbilibili_manage" },
    ];
    groups.forEach((g, i) => {
      const x = 0.4 + i * 3.25;
      s.addShape(pptx.shapes.ROUNDED_RECTANGLE, {
        x, y: 0.9, w: 3.1, h: 4.0, fill: { color: COLORS.card }, rectRadius: 0.1,
      });
      s.addText(g.title, {
        x, y: 1.05, w: 3.1, h: 0.45,
        fontSize: 16, fontFace: "Microsoft YaHei", bold: true, color: COLORS.accent, align: "center",
      });
      s.addText(g.items, {
        x: x + 0.2, y: 1.6, w: 2.7, h: 3.0,
        fontSize: 13, fontFace: "Consolas", color: COLORS.soft,
      });
    });
    s.addShape(pptx.shapes.ROUNDED_RECTANGLE, {
      x: 0.4, y: 5.2, w: 12.5, h: 1.8, fill: { color: COLORS.card }, rectRadius: 0.1,
    });
    s.addText("Skill ≠ Tool", {
      x: 0.7, y: 5.4, w: 12, h: 0.4,
      fontSize: 16, fontFace: "Microsoft YaHei", bold: true, color: COLORS.green,
    });
    s.addText(
      "Skill：领域能力包（YAML + Executor）→ 外环 SKILL 任务\nTool：原子 function（FunctionTool）→ 内环 tool_calls\n形态不同、调度环不同，可组合完成复杂用户意图",
      {
        x: 0.7, y: 5.85, w: 12, h: 1.0,
        fontSize: 14, fontFace: "Microsoft YaHei", color: COLORS.soft,
      }
    );
    addNotes(s, "不要逐个念工具名，按分组扫一眼。结尾强调 Skill≠Tool 对比框。");
  }

  // ---------- 11 Architecture main ----------
  {
    const s = pptx.addSlide();
    s.addShape(pptx.shapes.RECTANGLE, { x: 0, y: 0, w: 13.333, h: 7.5, fill: { color: COLORS.bg } });
    s.addText("项目架构 · 主链路", {
      x: 0.5, y: 0.2, w: 12, h: 0.4,
      fontSize: 24, fontFace: "Microsoft YaHei", bold: true, color: COLORS.text,
    });
    s.addImage({ path: path.join(assets, "architecture-main.png"), x: 0.55, y: 0.75, w: 12.2, h: 6.4 });
    addNotes(s, "按箭头讲：微信→调度（同用户串行异用户并行）→Handler→Bypass或Orchestrator→格式化回微信。Memory 贯穿。");
  }

  // ---------- 12 Dual loop ----------
  {
    const s = pptx.addSlide();
    s.addShape(pptx.shapes.RECTANGLE, { x: 0, y: 0, w: 13.333, h: 7.5, fill: { color: COLORS.bg } });
    s.addText("项目架构 · 双环 Agent（亮点）", {
      x: 0.5, y: 0.2, w: 12, h: 0.4,
      fontSize: 24, fontFace: "Microsoft YaHei", bold: true, color: COLORS.text,
    });
    s.addImage({ path: path.join(assets, "architecture-dual-loop.png"), x: 0.55, y: 0.7, w: 12.2, h: 6.5 });
    addNotes(s, "外环编排、内环 function-calling、B站子环有界规划。这是答辩架构亮点页。");
  }

  // ---------- 13 Perf quadrant ----------
  {
    const s = pptx.addSlide();
    s.addShape(pptx.shapes.RECTANGLE, { x: 0, y: 0, w: 13.333, h: 7.5, fill: { color: COLORS.bg } });
    s.addText("Agent 性能优化 · 四象限", {
      x: 0.5, y: 0.25, w: 12, h: 0.45,
      fontSize: 24, fontFace: "Microsoft YaHei", bold: true, color: COLORS.text,
    });
    const quads = [
      { title: "并行", body: "agent.max-parallelism=3\n消息调度 parallelism=4\n同用户保序 · 异用户并行\nPrompt：互不依赖工具同轮发出" },
      { title: "超时预算", body: "总执行 timeout=90s\n重规划 timeout=8s\nSkill 默认 30s（语音60s）\n各 HTTP 工具独立超时" },
      { title: "Token 节省", body: "工具结果截断 8k / 16k\n文档 8k · 网页 6k\n记忆近15轮+周期摘要\nUserFacingResultFormatter" },
      { title: "熔断缓存", body: "同工具失败上限=2\n重复调用去重\nDeepSeek 熔断/重试\nB站缓存30min · gap 2.5s" },
    ];
    quads.forEach((q, i) => {
      const x = 0.45 + (i % 2) * 6.4;
      const y = 0.95 + Math.floor(i / 2) * 3.05;
      s.addShape(pptx.shapes.ROUNDED_RECTANGLE, {
        x, y, w: 6.1, h: 2.85, fill: { color: COLORS.card }, rectRadius: 0.1,
      });
      s.addText(q.title, {
        x: x + 0.3, y: y + 0.25, w: 5.5, h: 0.45,
        fontSize: 20, fontFace: "Microsoft YaHei", bold: true, color: COLORS.accent,
      });
      s.addText(q.body, {
        x: x + 0.3, y: y + 0.85, w: 5.5, h: 1.7,
        fontSize: 14, fontFace: "Microsoft YaHei", color: COLORS.soft,
      });
    });
    addNotes(s, "四块依次讲完即可。数字已与 application.properties 对齐。");
  }

  // ---------- 14 Perf numbers + quality ----------
  {
    const s = pptx.addSlide();
    s.addShape(pptx.shapes.RECTANGLE, { x: 0, y: 0, w: 13.333, h: 7.5, fill: { color: COLORS.bg } });
    s.addText("Agent 性能优化 · 关键数字 & 质量守卫", {
      x: 0.5, y: 0.25, w: 12, h: 0.45,
      fontSize: 24, fontFace: "Microsoft YaHei", bold: true, color: COLORS.text,
    });
    const nums = [
      ["5", "内环 max-tool-rounds"],
      ["8", "总 tool calls 上限"],
      ["4", "每轮 tool calls"],
      ["1", "重规划次数预算"],
      ["0.60", "校验置信度阈值"],
      ["1500", "长文分片阈值"],
    ];
    nums.forEach((n, i) => {
      const x = 0.45 + (i % 6) * 2.15;
      s.addShape(pptx.shapes.ROUNDED_RECTANGLE, {
        x, y: 1.0, w: 2.0, h: 2.0, fill: { color: COLORS.card }, rectRadius: 0.1,
      });
      s.addText(n[0], {
        x, y: 1.2, w: 2.0, h: 0.8,
        fontSize: 32, fontFace: "Segoe UI", bold: true, color: COLORS.green, align: "center",
      });
      s.addText(n[1], {
        x: x + 0.1, y: 2.1, w: 1.8, h: 0.7,
        fontSize: 11, fontFace: "Microsoft YaHei", color: COLORS.soft, align: "center",
      });
    });
    s.addShape(pptx.shapes.ROUNDED_RECTANGLE, {
      x: 0.45, y: 3.4, w: 12.4, h: 3.5, fill: { color: COLORS.card }, rectRadius: 0.1,
    });
    s.addText("少重试 · 保质量", {
      x: 0.8, y: 3.7, w: 11.8, h: 0.45,
      fontSize: 18, fontFace: "Microsoft YaHei", bold: true, color: COLORS.amber,
    });
    s.addText(
      "• ToolValidationPipeline：结果置信度不足则 RETRY / REPLAN / ABORT，避免无效烧 Token\n• 重规划预算 replan.max-count=1，防止无限循环\n• Checkpoint（Mongo）断点续跑：崩溃后减少整单重做\n• 长文分片 wechat.reply.long-text-threshold=1500，适配微信消息长度",
      {
        x: 0.8, y: 4.3, w: 11.8, h: 2.3,
        fontSize: 15, fontFace: "Microsoft YaHei", color: COLORS.soft,
      }
    );
    addNotes(s, "数字卡片快速过；重点讲 Guard + Validation + Checkpoint 如何防止失控。");
  }

  // ---------- 15 Closing ----------
  {
    const s = pptx.addSlide();
    s.addShape(pptx.shapes.RECTANGLE, { x: 0, y: 0, w: 13.333, h: 7.5, fill: { color: COLORS.bg } });
    s.addShape(pptx.shapes.RECTANGLE, { x: 0, y: 0, w: 0.18, h: 7.5, fill: { color: COLORS.green } });
    s.addText("总结 & Q&A", {
      x: 0.8, y: 1.5, w: 11, h: 0.6,
      fontSize: 36, fontFace: "Microsoft YaHei", bold: true, color: COLORS.text,
    });
    s.addText(
      [
        "微信对话做产品体验，/console 做运维与答辩展示",
        "B站：标签推荐 + 订阅追更 + 定时推送，形成找片与追更闭环",
        "Skill / Tool 分层：领域能力包 × 原子 function-calling",
        "双环 Agent + Guard/缓存/并行：可控、省 Token、可扩展",
      ].map((t) => ({ text: "•  " + t + "\n", options: { breakLine: false } })),
      {
        x: 0.8, y: 2.5, w: 11.5, h: 3.0,
        fontSize: 18, fontFace: "Microsoft YaHei", color: COLORS.soft,
      }
    );
    s.addText("谢谢 · 欢迎提问", {
      x: 0.8, y: 6.2, w: 11, h: 0.5,
      fontSize: 20, fontFace: "Microsoft YaHei", color: COLORS.accent,
    });
    addNotes(s, "收束四句话后进入 Q&A。预备答：能力边界、打分权重、并行保序、Skill≠Tool。");
  }

  await pptx.writeFile({ fileName: outFile });
  console.log("Wrote", outFile);
}

main().catch((e) => {
  console.error(e);
  process.exit(1);
});
