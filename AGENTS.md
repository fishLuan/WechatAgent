# 项目：AI Agent 多工具并行开发

## 技术栈
- Spring Boot 4.1.0 + Java 21
- Maven (mvnw)
- iLink WeChat SDK: `io.github.lith0924:wechat-ilink-sdk:2.3.3`
- DashScope（阿里云百炼 API）
- Apache POI / PDFBox

## 项目位置
`/Users/lienqi/Downloads/AIdp`

## 构建命令
```bash
./mvnw clean compile
./mvnw clean package -DskipTests
```

## 环境变量
- `BAILIAN_API_KEY` — 阿里云百炼 API 密钥（必填）
- `DINGTALK_WEBHOOK` — 钉钉机器人 Webhook（可选）
- `DINGTALK_SECRET` — 钉钉机器人签名密钥（可选）

## 工作原则
**原则①**：改代码前必须先分析利弊，征得用户同意再动手。不得直接修改代码。

## Git 协作规范（团队强制）

### 核心原则
1. **Master 分支**：唯一稳定主干，禁止直接 Push
2. **功能隔离**：一人一分支
3. **准入机制**：仅 PR/MR 合并

### 标准开发流程
```bash
# 1. 同步主干最新代码
git checkout master
git pull origin master

# 2. 创建个人独立功能分支（命名规范: feature/工具名-tool）
git checkout -b feature/xxx-tool

# 3. 独立开发，提交并推送
git add .
git commit -m "完成xxx工具开发"
git push origin feature/xxx-tool

# 4. 在 GitHub/GitLab 网页端提交 PR/MR → Master
# 5. 等待审核通过后合并
# 6. 任意功能合并后，全员同步：
git checkout master
git pull origin master
```

### 冲突处理
- 仅当前功能开发者本人处理冲突
- 解决后重新提交、完成合并

### 硬性约束
1. 开启 Master 分支保护，关闭直接 Push 权限
2. 禁止多人共用一个功能分支开发
3. 禁止在 Master 分支直接写业务代码
4. 功能合并上线后，删除远程废弃 Feature 分支
