const http = require("http");
const fs = require("fs");
const path = require("path");

const root = __dirname;
const mime = {
  ".html": "text/html; charset=utf-8",
  ".svg": "image/svg+xml",
  ".png": "image/png",
  ".js": "text/javascript",
  ".css": "text/css",
};

const server = http.createServer((req, res) => {
  let p = decodeURIComponent((req.url || "/").split("?")[0]);
  if (p === "/") p = "/mockups/console-dash.html";
  const rel = p.replace(/^\/+/, "").replace(/\//g, path.sep);
  const fp = path.resolve(root, rel);
  if (!fp.startsWith(path.resolve(root)) || !fs.existsSync(fp) || fs.statSync(fp).isDirectory()) {
    res.writeHead(404, { "Content-Type": "text/plain" });
    res.end("not found: " + fp);
    return;
  }
  res.writeHead(200, { "Content-Type": mime[path.extname(fp)] || "application/octet-stream" });
  fs.createReadStream(fp).pipe(res);
});

server.listen(8765, "127.0.0.1", () => {
  console.log("serving", root, "at http://127.0.0.1:8765");
});
