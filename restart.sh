#!/usr/bin/env bash
# ---------------------------------------------------------------
# 改完 **Java 代码** 后跑这个。
#
# 什么时候要跑：动了 src/main/java/、pom.xml、application*.yml —— 也就是**后端**。
#               只改前端（src/main/resources/static/）**不用**跑这个，跑 ./refresh.sh 就行。
#
# 为什么要重启：**Java 类是 JVM 启动时一次性加载的**，进程跑起来之后不会去读新的 .class。
#               而静态资源是**按请求**去读 target/classes 的（所以前端换个文件就生效，见 refresh.sh）。
#
# 怎么停：**按端口找 PID 杀**（AGENTS.md 的规矩）——杀外层 shell / mvn **不会**连带杀掉
#         它启动的 java 孙进程，端口会一直占着。本脚本就是照这条实现的。
#
# ⚠️ Windows / Git Bash 的坑：直接写 `taskkill /F /PID 123` 会被 Git Bash 把 `/F` 当成路径
#    改写成 `F:/`，报"无效参数/选项 - 'F:/'"。所以这里必须带 MSYS_NO_PATHCONV=1。
# ---------------------------------------------------------------
set -uo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
PORT="${PORT:-8080}"
LOG="$ROOT/app-run.log"
MVN="${MVN:-mvn}"

# 本机 Maven 是手动装在用户目录的（见 AGENTS.md），做个兜底
if ! command -v "$MVN" >/dev/null 2>&1; then
  for c in "$HOME"/tools/apache-maven-*/bin/mvn; do
    [ -x "$c" ] && MVN="$c" && break
  done
fi

cd "$ROOT" || exit 1

# ---- 1. 找占用端口的 PID（注意：Git Bash 里用 grep，不是 cmd 的 findstr）
PID="$(netstat -ano 2>/dev/null | grep -E "[:.]$PORT[[:space:]]" | grep -i LISTENING | head -1 | awk '{print $NF}')"

if [ -z "$PID" ]; then
  echo "[1/3] :$PORT 上没有进程在跑，直接启动。"
else
  # ---- 2. 先说清要停的是谁，再杀
  echo "[1/3] 停掉占用 $PORT 的进程（PID $PID）…"
  powershell -NoProfile -Command "Get-CimInstance Win32_Process -Filter 'ProcessId=$PID' | ForEach-Object { '      ' + \$_.Name + '  启动于 ' + \$_.CreationDate }" 2>/dev/null
  if ! MSYS_NO_PATHCONV=1 taskkill /F /PID "$PID" >/dev/null 2>&1; then
    echo "  ✗ 杀不掉 PID $PID（可能需要管理员权限）" >&2
    exit 1
  fi
  # ---- 3. 等端口真的释放
  for _ in $(seq 1 15); do
    netstat -ano 2>/dev/null | grep -E "[:.]$PORT[[:space:]]" | grep -qi LISTENING || break
    sleep 1
  done
  if netstat -ano 2>/dev/null | grep -E "[:.]$PORT[[:space:]]" | grep -qi LISTENING; then
    echo "  ✗ 端口 $PORT 仍被占用" >&2
    exit 1
  fi
  echo "      端口已释放"
fi

# ---- 4. 后台起新进程，日志落 app-run.log
echo "[2/3] 启动服务（mvn spring-boot:run）…"
nohup "$MVN" spring-boot:run > "$LOG" 2>&1 &
echo "      日志：$LOG"

# ---- 5. 等健康（mvn 要先编译，比前端慢，给到 ~90 秒）
echo "[3/3] 等 /api/health 就绪…"
CODE=""
for _ in $(seq 1 45); do
  CODE="$(curl -s -o /dev/null -w '%{http_code}' --max-time 3 "http://localhost:$PORT/api/health" 2>/dev/null)"
  [ "$CODE" = "200" ] && break
  sleep 2
done

if [ "$CODE" = "200" ]; then
  echo
  echo "✓ 服务已起（:$PORT）。日志：$LOG"
  echo "  验证新接口有没有生效：curl -s -o /dev/null -w '%{http_code}\n' http://localhost:$PORT/api/health"
  echo "  （新增接口若返回 401=要登录即已生效；404=这个进程里还没有它）"
else
  echo "  ✗ 启动失败（HTTP ${CODE:-无响应}），日志末尾：" >&2
  tail -20 "$LOG" >&2
  exit 1
fi

# ---- [4/4] 规格↔代码一致性（只打印、不阻断）----
# 为什么挂在已有的脚本上：改了 Java 通常也顺手改了 docs，搭在这里就等于**自动跑一遍**，
# 不用再指望人记得（`AGENTS.md` 那句"改完 docs 跑一次"一旦靠记性就会衰减）。
# 规则见 docs/00 §0.9.5。**只打印不中断**——报告里有 🚧 之类"已交接、不急着做"的项。
echo
echo "[4/4] 规格↔代码一致性检查（规则见 docs/00 §0.9.5）…"
PY=""
command -v python  >/dev/null 2>&1 && PY=python
[ -z "$PY" ] && command -v python3 >/dev/null 2>&1 && PY=python3
if [ -z "$PY" ]; then
  echo "      (跳过：没找到 python)"
elif ! "$PY" "$ROOT/tools/spec_check.py"; then
  echo "      ⚠ 上面有 ❌ 项——不影响本次重启，但该派的活别漏（交接单.md）"
fi
