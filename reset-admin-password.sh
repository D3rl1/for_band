#!/bin/bash
# ---------------------------------------------------------------
# 管理员应急重置密码（**忘密码时用**）。规格：SECURITY.md「F7 的实现形态」+ docs/01 §1.4。
#
# 它做且只做一件事：把管理员账号的密码换成一个新的随机值并打印出来。三步**按序**做完：
#   ① 写新哈希 + 清 initial_password   ② 删该账号**全部** user_token   ③ 写 operation_log 留痕
# 第二步不能省、也不能放到第一步之前：旧 token 有 30 天有效期，不删的话"被盗的 token"照样能用。
#
# ⚠️ **为什么没有网页入口、不开端口**：那等于把"忘记密码"变成一条网络攻击路径，而这个项目
#    **只有一个管理员账号**（SECURITY.md G4 是个已知单点）。它全部的"权限"就是
#    **能 SSH 上这台机器 + 有 root**（所以要 sudo）。
#
# ⚠️ **为什么哈希要跑 jar 里那个类**：BCrypt 的实现在全项目只允许有一份。应用那三处
#    （AdminSeeder / AuthService / MemberService）用的都是 Spring Security 的
#    `BCryptPasswordEncoder` **无参构造**（默认 10 轮）。这里另写一份实现，哪天两边对不上
#    就是"密码明明重置了却登不进"。所以脚本从**应用自己的 jar** 里把 spring-security-crypto
#    掏出来、用 jshell 调它。⚠️ 构造参数**必须留空**，与那三处逐字一致。
#
# ⚠️ **新密码只打印到 stdout**：它就是明文凭据，落盘等于把"备份文件也要当机密"那条风险
#    再次引回来（docs/12 §12.5 硬要求）。所以：**不写文件、不进日志、不进命令行参数**
#    （argv 在 ps 里对同机其他用户可见）。
#
# ⚠️ 脚本里**不写管道**（docs/12 §12.6 尾注的运维口径）：管道会把前半段的失败吞掉，
#    出问题时看不出是哪一步断的。
#
# 用法（服务器上）：
#     sudo /opt/band/reset-admin-password.sh
#
# 本机自测（不动上面这套服务器口径，用环境变量覆盖；Windows Git Bash 没有 root，
# 所以要显式开 ALLOW_NON_ROOT —— 这个口子只给自测用）：
#     JAR=target/for-band-0.0.1-SNAPSHOT.jar CONF=src/main/resources/application-local.yml \
#     DB_NAME=band_test NEW_PASSWORD=TestAbcd1234 ASSUME_YES=1 ALLOW_NON_ROOT=1 \
#     ./reset-admin-password.sh
# ---------------------------------------------------------------
set -uo pipefail

# ---- 可覆盖的开关（默认值全是服务器口径，所以自测不用改脚本正文）----
JAR="${JAR:-/opt/band/band.jar}"                                   # ⚠️ 服务器上 jar 名不是 band.jar，按实际指
CONF="${CONF:-$(dirname "$JAR")/application-local.yml}"            # 库密码只在这个文件里（docs/12 §12.3）
MYSQL="${MYSQL:-mysql}"
UNZIP="${UNZIP:-unzip}"
JSHELL="${JSHELL:-}"
ADMIN_ACCOUNT="${ADMIN_ACCOUNT:-}"                                 # 用来消歧：库里不止一个 is_system=1 时必填
NEW_PASSWORD="${NEW_PASSWORD:-}"                                   # 给定则不再随机（自测用）
ASSUME_YES="${ASSUME_YES:-}"                                       # 1 = 跳过确认（自测用）
ALLOW_NON_ROOT="${ALLOW_NON_ROOT:-}"                               # 1 = 允许非 root 跑（**只给本机自测**）

TMP=""
cleanup() { [ -n "$TMP" ] && rm -rf "$TMP"; return 0; }
trap cleanup EXIT
die() { echo "✗ $*" >&2; exit 1; }

# ---- 0. 前置检查：一次性把"环境不对"和"参数不对"挡在动手之前 ----
if [ "$(id -u)" != "0" ]; then
  [ "$ALLOW_NON_ROOT" = "1" ] || die "必须用 root 跑（服务器上 SSH 用户是 ubuntu ⇒ 前面加 sudo）"
  echo "⚠️ 正在以非 root 身份跑（ALLOW_NON_ROOT=1）——这只适用于本机自测。"
fi
command -v "$MYSQL" >/dev/null 2>&1 || die "找不到 $MYSQL"
[ -r "$JAR" ]        || die "读不到 jar：$JAR
  ⚠️ 服务器上 jar 名**不是** band.jar，用 JAR=/opt/band/实际名字.jar 指过去（docs/12 §12.6）"
[ -r "$CONF" ]       || die "读不到配置：$CONF（数据库密码就在这个文件里，docs/12 §12.3）"
command -v "$UNZIP" >/dev/null 2>&1 || die "找不到 $UNZIP（要从 jar 里掏出加密库）"
if [ -z "$JSHELL" ]; then
  if command -v jshell >/dev/null 2>&1; then
    JSHELL="$(command -v jshell)"
  elif [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/jshell" ]; then
    JSHELL="$JAVA_HOME/bin/jshell"
  fi
fi
[ -n "$JSHELL" ] || die "找不到 jshell。它属于 **JDK**（JRE 里没有）——装上再跑：
      apt-get install -y openjdk-17-jdk-headless
  ⚠️ 这里**故意不退化成自己实现一份 bcrypt**：那就成了第二份实现（理由见文件头）。"
command -v "$JSHELL" >/dev/null 2>&1 || [ -x "$JSHELL" ] || die "jshell 不可执行：$JSHELL"

echo "[1/5] 读配置…"

# ---- 1. 数据库连接：环境变量 > 配置文件 > 内置默认值 ----
# ⚠️ **不能用 sed/grep 一把梭**：application-local.yml 里有**两个** password: 键
#    （band.admin.password 与 spring.datasource.password），抓错了的表现是"密码是对的却连不上"。
#    所以用一个只在 spring.datasource 段里找键的小状态机（按缩进判层级）。
#    ⚠️ 二级键取出来时**必须去掉前导空格**（`"  datasource"` 不等于 `"datasource"`，这条踩过）。
CONF_KEYS="$(awk '
  /^[A-Za-z]/   { top = $0; sub(/:.*/, "", top); sub2 = ""; next }
  /^  [A-Za-z]/ { sub2 = $0; sub(/:.*/, "", sub2); gsub(/^[[:space:]]+/, "", sub2); next }
  /^    [A-Za-z_-]+:/ {
      if (top != "spring" || sub2 != "datasource") next
      key = $0; sub(/:.*/, "", key); gsub(/^[[:space:]]+/, "", key)
      val = $0; sub(/^[^:]*:[[:space:]]*/, "", val); gsub(/^["'"'"']|["'"'"']$/, "", val)
      if (key == "url")      print "url=" val
      if (key == "username") print "username=" val
      if (key == "password") print "password=" val
  }
' "$CONF")"

CONF_URL=""; CONF_USER=""; CONF_PASS=""
while IFS='=' read -r k v; do
  case "$k" in
    url)      CONF_URL="$v" ;;
    username) CONF_USER="$v" ;;
    password) CONF_PASS="$v" ;;
  esac
done <<< "$CONF_KEYS"

CONF_HOST=""; CONF_PORT=""; CONF_DB=""
if [ -n "$CONF_URL" ] && [[ "$CONF_URL" =~ jdbc:mysql://([^:/]+):([0-9]+)/([^?]+) ]]; then
  CONF_HOST="${BASH_REMATCH[1]}"; CONF_PORT="${BASH_REMATCH[2]}"; CONF_DB="${BASH_REMATCH[3]}"
fi
# ⚠️ 优先级**只能是** 环境变量 > 配置文件 > 内置默认值：`${VAR:-…}` 的展开顺序就是这个意思。
#    别把配置文件的值直接赋给 DB_* —— 那样自测时 `DB_NAME=band_test` 会被配置里的库名盖掉。
DB_HOST="${DB_HOST:-${CONF_HOST:-127.0.0.1}}"
DB_PORT="${DB_PORT:-${CONF_PORT:-3306}}"
DB_NAME="${DB_NAME:-${CONF_DB:-band}}"
DB_USER="${DB_USER:-${CONF_USER:-band_app}}"
DB_PASSWORD="${DB_PASSWORD:-$CONF_PASS}"
[ -n "$DB_PASSWORD" ] || die "没取到数据库密码。给一个再跑：DB_PASSWORD='…' $0
  （或者检查 $CONF 里 spring.datasource 段下的 password 是不是还在）"

# MYSQL_PWD 而不是 -p<密码>：后者会出现在 ps 的命令行里，同机其他用户看得见
mysql_q() { MYSQL_PWD="$DB_PASSWORD" "$MYSQL" -h"$DB_HOST" -P"$DB_PORT" -u"$DB_USER" -N -B "$DB_NAME" -e "$1"; }

# ---- 2. 定目标账号（不写死 "admin"：账号名来自配置 band.admin.account）----
ADMIN_ID=""; ADMIN_ACCOUNT_FOUND=""
while IFS=$'\t' read -r id acct; do
  [ -n "$id" ] || continue
  if [ -n "$ADMIN_ACCOUNT" ] && [ "$acct" != "$ADMIN_ACCOUNT" ]; then
    continue
  fi
  if [ -n "$ADMIN_ID" ]; then
    die "库里有多个 is_system=1 的账号，用 ADMIN_ACCOUNT=<登录名> 指定一个再跑"
  fi
  ADMIN_ID="$id"; ADMIN_ACCOUNT_FOUND="$acct"
done <<< "$(mysql_q "SELECT id, account FROM member WHERE is_system = 1")"

[ -n "$ADMIN_ID" ] || die "没找到管理员系统账号（member 里 is_system=1 的行）——是不是连错库了？当前是 $DB_NAME"

echo "      目标：$ADMIN_ACCOUNT_FOUND（id=$ADMIN_ID）@ $DB_HOST:$DB_PORT/$DB_NAME"

if [ "$ASSUME_YES" != "1" ]; then
  printf '确认重置「%s」的密码？该账号**所有设备会立刻掉线** [y/N] ' "$ADMIN_ACCOUNT_FOUND"
  read -r ans || ans=""
  case "$ans" in
    y|Y|yes|YES) ;;
    *) echo "已取消，什么都没改。"; exit 1 ;;
  esac
fi

# ---- 3. 出哈希 ----
echo "[2/5] 生成新密码与哈希…"
if [ -z "$NEW_PASSWORD" ]; then
  # 复用应用自己的字符表（已排除 0/O/1/l/I 这些肉眼易混的字符）。
  # 只在这里出现一次，且**只经环境变量**传给 jshell（不进 argv）。
  ALPHABET="ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789"
  NEW_PASSWORD=""
  for _ in $(seq 1 16); do
    NEW_PASSWORD="$NEW_PASSWORD${ALPHABET:$((RANDOM % ${#ALPHABET})):1}"
  done
fi

TMP="$(mktemp -d)" || die "建不了临时目录"

# 从应用自己的 jar 里掏出两个库、解成 class 文件目录，交给 jshell。
# ⚠️ **spring-jcl 不能少**：`BCryptPasswordEncoder` 的构造函数就要 commons-logging 的 `LogFactory`，
#    只放 crypto 那个包会当场 NoClassDefFoundError（实测踩过，注意这是**构造时**就炸、不是算到一半才炸）。
# ⚠️ 用"解开成普通目录"而不是 `--class-path "目录/*"`：Windows 上的 jshell 认不出那个通配写法
#    （实测报"找不到文件"），顺带躲开 classpath 分隔符 Linux 用 `:`、Windows 用 `;` 的坑。
mkdir -p "$TMP/unpack" || die "建不了临时目录"
for pat in 'BOOT-INF/lib/spring-security-crypto-*.jar' 'BOOT-INF/lib/spring-jcl-*.jar'; do
  "$UNZIP" -j -o -q "$JAR" "$pat" -d "$TMP/jar" \
    || die "解不开 jar 里的 $pat —— 是不是指到了非 Spring Boot 的 jar？"
done
for j in "$TMP"/jar/*.jar; do
  [ -r "$j" ] || die "jar 里没找到 spring-security-crypto / spring-jcl，中止。"
  "$UNZIP" -o -q "$j" -d "$TMP/unpack" || die "解不开 $j"
done
[ -f "$TMP/unpack/org/springframework/security/crypto/bcrypt/BCryptPasswordEncoder.class" ] \
  || die "解出来的类里没有 BCryptPasswordEncoder，中止。"

# 这段脚本里**没有明文密码**：明文只经 BAND_NEW_PW 环境变量进 jshell。
JS="$TMP/hash.jsh"
cat > "$JS" <<'JSEOF'
var enc = new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder();
var pw = System.getenv("BAND_NEW_PW");
var h = enc.encode(pw);
System.out.println("HASH=" + h);
System.out.println("VERIFY=" + enc.matches(pw, h));
/exit
JSEOF

export BAND_NEW_PW="$NEW_PASSWORD"
OUT="$("$JSHELL" -s --class-path "$TMP/unpack" "$JS" 2>&1)"
unset BAND_NEW_PW

# ⚠️ 去掉 \r：Windows 上 java 的 println 出行尾是 \r\n，留着它哈希就变成 61 位
#    （Linux 上没这问题、本机自测才有——正是下面那条长度校验逮住的）。
OUT="${OUT//$'\r'/}"

HASH=""; VERIFY=""
while IFS= read -r line; do
  case "$line" in
    HASH=*)   HASH="${line#HASH=}" ;;
    VERIFY=*) VERIFY="${line#VERIFY=}" ;;
  esac
done <<< "$OUT"

case "$HASH" in
  \$2a\$10\$*) ;;
  *) die "算出来的不是预期形状的 bcrypt 哈希（期望 \$2a\$10\$…）。jshell 输出：
$OUT" ;;
esac
[ "${#HASH}" -eq 60 ] || die "哈希长度不是 60（实际 ${#HASH}），中止。"
[ "$VERIFY" = "true" ] || die "自检失败：算出来的哈希对不上这个新密码，**已中止、没有写库**。"
echo "      哈希已生成并自检通过（\$2a\$10\$，与应用同强度）"

# ---- 4. 三步，一个事务 ----
echo "[3/5] 重置密码 + 作废旧 token + 留痕（一个事务）…"
SQL="START TRANSACTION;
UPDATE member SET password_hash = '$HASH', initial_password = NULL, updated_at = NOW() WHERE id = $ADMIN_ID;
DELETE FROM user_token WHERE member_id = $ADMIN_ID;
SET @deleted = ROW_COUNT();
INSERT INTO operation_log (operator_id, action, target_type, target_id, \`before\`, \`after\`, ip, created_at)
VALUES ($ADMIN_ID, 'ADMIN_PASSWORD_EMERGENCY_RESET', 'MEMBER', $ADMIN_ID, NULL,
        JSON_OBJECT('tokensDeleted', @deleted), NULL, NOW());
COMMIT;"

mysql_q "$SQL" || die "写库失败——**三步都没生效**（一个事务，没 COMMIT 会整体回滚），密码没有打印出来。"

# ---- 5. 验完再打印 ----
echo "[4/5] 核对结果…"
LEFT="$(mysql_q "SELECT COUNT(*) FROM user_token WHERE member_id = $ADMIN_ID")"
[ "$LEFT" = "0" ] || die "旧 token 没清干净（还剩 $LEFT 条）——**没有打印新密码**，请人工查。"

echo "[5/5] 完成。"
echo
echo "  账号：$ADMIN_ACCOUNT_FOUND"
echo "  新密码（**只显示这一次**，请立刻存进密码管理器）："
echo
echo "      $NEW_PASSWORD"
echo
echo "  · 该账号原有登录已全部失效（user_token 已清空），请重新登录。"
echo "  · 操作日志已留痕：ADMIN_PASSWORD_EMERGENCY_RESET"
echo "  · 这个脚本没有把密码写进任何文件或日志——上面这段就是唯一的副本。"
unset NEW_PASSWORD
