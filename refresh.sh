#!/usr/bin/env bash
# ---------------------------------------------------------------
# 改完前端后跑这个。
#
# 什么时候要跑：动了 src/main/resources/static/ 下的**任何文件**
#（HTML / CSS / JS / 字体）。改了 Java 代码不用跑（那要重启）。
#
# 为什么要跑：Spring Boot 从 **target/classes** 读静态资源，
# 只改 src/ 不会自动生效 —— 浏览器拿到的还是旧版，你会以为"改了没用"。
#
# 为什么不用重启：实测确认应用是**按请求读 target/classes 里的文件**，
# 所以**只要把资源重新拷一遍就行**。这条最省事、也不打断正在跑的服务。
#
# 万一拷完还是旧的（少数情况），脚本会告警，那时才需要重启后端。
# ---------------------------------------------------------------
set -uo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
PORT="${PORT:-8080}"
PROBE="band-nav.js"            # 拿一个前端文件当探针比对哈希
MVN="${MVN:-mvn}"

# 本机 Maven 是手动装在用户目录的（见 AGENTS.md），做个兜底
if ! command -v "$MVN" >/dev/null 2>&1; then
  for c in "$HOME"/tools/apache-maven-*/bin/mvn; do
    [ -x "$c" ] && MVN="$c" && break
  done
fi

cd "$ROOT" || exit 1

echo "[1/3] 重新构建资源…"
if ! "$MVN" -q process-resources; then
  echo "  ✗ 构建失败" >&2
  exit 1
fi

SRC="$ROOT/src/main/resources/static/$PROBE"
if [ ! -f "$SRC" ]; then
  echo "  ✗ 找不到探针文件 $SRC" >&2
  exit 1
fi
LOCAL_HASH="$(sha256sum "$SRC" | awk '{print $1}')"
echo "      源文件  ${LOCAL_HASH:0:12}…  $PROBE"

echo "[2/3] 等 HTTP 服务可用（:$PORT）…"
CODE=""
for _ in $(seq 1 30); do
  CODE="$(curl -s -o /dev/null -w '%{http_code}' --max-time 3 "http://localhost:$PORT/api/health" 2>/dev/null)"
  [ "$CODE" = "200" ] && break
  sleep 2
done

if [ "$CODE" != "200" ]; then
  echo "  ✗ :$PORT 上没有可用的服务（HTTP ${CODE:-无响应}）" >&2
  echo "    若刚才改的是 Java 代码（不是前端），需要重启后端：" >&2
  echo "      停掉占用 $PORT 的进程 → mvn spring-boot:run" >&2
  exit 1
fi
echo "      服务正常"

echo "[3/3] 校验服务端拿到的与源一致…"
SERVED_HASH="$(curl -s --max-time 5 "http://localhost:$PORT/$PROBE" | sha256sum | awk '{print $1}')"
echo "      服务端  ${SERVED_HASH:0:12}…"

if [ "$LOCAL_HASH" = "$SERVED_HASH" ]; then
  echo
  echo "✓ 一致 —— 浏览器 Ctrl+F5 硬刷新就能看到改动。"
else
  echo
  echo "✗ 不一致：服务端还在发旧文件。" >&2
  echo "  说明这个实例把资源缓存住了，需要重启后端：" >&2
  echo "    ./restart.sh（或：停掉占用 $PORT 的进程 → mvn spring-boot:run）" >&2
  exit 1
fi

# ---- [4/4] 页面可达性体检：每个 static/*.html 都得在**别处**被引用过，否则界面上够不着 ----
# 为什么查这个：新建了页面却忘了挂入口时，**没有任何东西会报错**——页面能直接打开、接口也通，
# 就是没人点得到（2026-09-23 真发生过一次：recordings.html 忘了进 navItems）。
#
# 判据：这个文件名要出现在 **app.js**（navItems / offMenuTitles / href）或**别的 html**（页面里的链接）里。
# 详情页（event-detail / piece-detail / concert-detail）就是靠"从列表页链过去"过关的，不必进导航。
# 只警告、不中断：个别页面"故意不做入口"是合法的。
echo
echo "[4/4] 检查页面有没有入口（能否从别处点到）…"
STATIC_DIR="$ROOT/src/main/resources/static"
MISSING=""
COUNT=0
for f in "$STATIC_DIR"/*.html; do
  name="$(basename "$f")"
  case "$name" in
    index.html|login.html) continue ;;   # index 是纯跳转壳、login 是登录页，都不进导航
  esac
  COUNT=$((COUNT + 1))
  # 在除自己以外的 static 文件里找引用（-r + 排除自身）
  refs="$(grep -rl --include='*.html' --include='*.js' -- "$name" "$STATIC_DIR" 2>/dev/null | grep -v "/$name\$" | head -1)"
  [ -z "$refs" ] && MISSING="$MISSING $name"
done

if [ -n "$MISSING" ]; then
  echo "  ⚠ 这些页面在别处没有任何引用，界面上点不到：" >&2
  for name in $MISSING; do echo "      $name" >&2; done
  echo "    要么进 app.js 的 navItems（主导航）/ offMenuTitles，要么从某个页面链过去" >&2
else
  echo "      ✓ $COUNT 个页面都有入口"
fi
