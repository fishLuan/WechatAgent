const puppeteer = require("puppeteer");
const path = require("path");
const fs = require("fs");

const outDir = path.join(__dirname, "assets");
fs.mkdirSync(outDir, { recursive: true });

const pages = [
  { url: "http://127.0.0.1:8765/mockups/console-dash.html", file: "console-dash.png", w: 1280, h: 800 },
  { url: "http://127.0.0.1:8765/mockups/console-tools.html", file: "console-tools.png", w: 1280, h: 800 },
  { url: "http://127.0.0.1:8765/mockups/console-tasks.html", file: "console-tasks.png", w: 1280, h: 720 },
  { url: "http://127.0.0.1:8765/mockups/console-history.html", file: "console-history.png", w: 1280, h: 720 },
  { url: "http://127.0.0.1:8765/mockups/wechat-demo.html", file: "wechat-demo.png", w: 480, h: 900 },
  { url: "http://127.0.0.1:8765/assets/architecture-main.svg", file: "architecture-main.png", w: 1200, h: 720 },
  { url: "http://127.0.0.1:8765/assets/architecture-dual-loop.svg", file: "architecture-dual-loop.png", w: 1200, h: 680 },
];

(async () => {
  const browser = await puppeteer.launch({
    headless: true,
    args: ["--no-sandbox", "--disable-setuid-sandbox"],
  });
  for (const p of pages) {
    const page = await browser.newPage();
    await page.setViewport({ width: p.w, height: p.h, deviceScaleFactor: 2 });
    await page.goto(p.url, { waitUntil: "networkidle0", timeout: 60000 });
    await page.screenshot({
      path: path.join(outDir, p.file),
      fullPage: false,
      type: "png",
    });
    console.log("saved", p.file);
    await page.close();
  }
  await browser.close();
  console.log("done");
})().catch((e) => {
  console.error(e);
  process.exit(1);
});
