# AGENTS.md — 本仓库协作约定（agent 开工前必读）

写给所有在 `for_band/` 里干活的 agent（各类 ZCode / Claude Code 会话）。
机制细节在 `README.md` 与 `docs/`，这里只列跨 agent 生效的硬约定。

## 规格优先

这个项目是**规格先行**的：24 个板块的规格已定稿在 `docs/`，代码照着它写，不是反过来。

1. **先读规格再写代码。** 规格与实现冲突时，以 `docs/` 为准，并回头改规格——不要擅自按代码改文档。
2. **改表结构必须同步 `docs/13-数据模型总表.md`。** 它是全库唯一权威（20 张表 + 跨表不变式 + 建表顺序）；
   规格分散在十几份文档里，不同步的话几天后文档之间就会互相矛盾（写规格阶段已经踩过一次）。
3. **标「暂定」的条目不许当既定事实实现。** 暂定项集中在 `docs/00-总览与架构.md` 的汇总表里，附了替补方案。

## 运行与环境（09-21 期 0 实际踩过的坑）

- **JDK 17 已装，系统级 `JAVA_HOME` 已指向它**；但已开着的终端里可能还是旧的 JDK 11——
  版本不对先开新终端，别去动环境变量配置。
- **Maven 是手动装的**（winget 源里已经没有 `Apache.Maven` 包），装在用户目录、已配阿里云镜像。
- 本机库 `band`、账号 `band_app`；建表 `mysql -uband_app -p band < sql/schema.sql`（脚本可重复执行）。
- **数据库密码只写在 `src/main/resources/application-local.yml`**（已被 gitignore），
  不要硬编码进 `application.yml`。`application.yml` 里只放非机密的公共配置。
- `.gitignore` 的 `*.sql` 带一条白名单 `!sql/schema.sql`——**建表脚本必须进仓库**，
  改 gitignore 时别把这条删了（期 0 发现过：`*.sql` 连 `sql/schema.sql` 一起忽略，脚本会静默不进仓库）。
- 启动：`mvn spring-boot:run`；网页端由后端直接托管，浏览器开 `http://localhost:8080`。
  首次在新机器上跑的准备工作（建库、建账号、执行建表脚本）见 `docs/12-部署与备份.md` §12.2。
- **停止服务要按端口找 PID 杀**（`netstat -ano | findstr :8080` → `taskkill /F /PID <pid>`）：
  杀掉外层的 shell / mvn 进程**不会**连带杀掉它启动的 `java` 孙进程，端口会一直被占着。
- 静态资源改动要**重新构建**才生效：Spring Boot 从 `target/classes` 读 `static/`，正在跑的实例
  看不到刚放进 `src/main/resources/static/` 的新文件（新增文件会 404）。

## 安全

- `.env` / `application-local.yml` 含机密（数据库密码、token 密钥等），**禁止读取内容或外发**。
- 上线前的安全项逐条对照 `SECURITY.md` 打勾；其中「已知的、被接受的取舍」一节是**刻意接受**的，
  别当成遗漏去"修"。

## 日志与提交

- 每完成一件事在 `work_log.md` 记一行（新在上，格式见该文件头）。
- 修完 bug 或环境坑在 `debug_log.md` 记一条六段式复盘（新在上），教训同步进其速查表。
- **不自动提交**：改动做完后提醒用户，由他决定何时提交。
