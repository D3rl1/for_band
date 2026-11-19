#!/usr/bin/env bash
# 无 Gradle 构建：aapt2 -> javac -> d8 -> 注入 classes.dex -> zipalign -> apksigner。
# 用法：FORBAND_ORIGIN=https://<服务器IP> ./android/build.sh [--publish]
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO="$(cd "$ROOT/.." && pwd)"
BUILD="$ROOT/build"
DIST="$ROOT/dist"
MIN_SDK=26
TARGET_SDK=34

die() { echo "错误：$*" >&2; exit 1; }
note() { echo "==> $*"; }

ORIGIN="${FORBAND_ORIGIN:-}"
[[ -n "$ORIGIN" ]] || die "请设置 FORBAND_ORIGIN，例如 FORBAND_ORIGIN=https://<服务器IP>"
ORIGIN="${ORIGIN%/}"
[[ "$ORIGIN" =~ ^https://[A-Za-z0-9.:-]+$ ]] || die "FORBAND_ORIGIN 必须是 https://主机（可带端口），当前：$ORIGIN"

VC="$(grep -E '^versionCode=' "$ROOT/version.properties" | head -n1 | cut -d= -f2- | tr -d '[:space:]')"
VN="$(grep -E '^versionName=' "$ROOT/version.properties" | head -n1 | cut -d= -f2- | tr -d '[:space:]')"
NOTES="$(grep -E '^notes=' "$ROOT/version.properties" | head -n1 | cut -d= -f2- | sed 's/^[[:space:]]*//')"
[[ "$VC" =~ ^[0-9]+$ ]] || die "versionCode 必须是整数"
[[ "$VN" =~ ^[0-9A-Za-z._-]+$ ]] || die "versionName 只能含字母、数字、点、下划线、连字符"

if [[ -n "${ANDROID_HOME:-}" ]]; then
    SDK="$ANDROID_HOME"
elif [[ -n "${ANDROID_SDK_ROOT:-}" ]]; then
    SDK="$ANDROID_SDK_ROOT"
elif [[ -n "${LOCALAPPDATA:-}" ]]; then
    SDK="$LOCALAPPDATA/Android/Sdk"
else
    die "找不到 Android SDK：请设置 ANDROID_HOME"
fi
[[ -d "$SDK" ]] || die "Android SDK 不存在：$SDK"

BT="$(ls -1 "$SDK/build-tools" 2>/dev/null | sort -V | tail -n1 || true)"
[[ -n "$BT" ]] || die "找不到 build-tools"
BT="$SDK/build-tools/$BT"

ANDROID_JAR=""
while IFS= read -r platform; do
    if [[ -f "$SDK/platforms/$platform/android.jar" ]]; then
        ANDROID_JAR="$SDK/platforms/$platform/android.jar"
        break
    fi
done < <(ls -1 "$SDK/platforms" 2>/dev/null | sort -Vr)
[[ -n "$ANDROID_JAR" ]] || die "找不到 platforms/*/android.jar"

tool() {
    if [[ -f "$BT/$1.exe" ]]; then echo "$BT/$1.exe"; else echo "$BT/$1"; fi
}
AAPT2="$(tool aapt2)"
ZIPALIGN="$(tool zipalign)"
D8_JAR="$BT/lib/d8.jar"
APKSIGNER_JAR="$BT/lib/apksigner.jar"
[[ -f "$D8_JAR" ]] || die "找不到 d8.jar"
[[ -f "$APKSIGNER_JAR" ]] || die "找不到 apksigner.jar"

note "SDK: $SDK"
note "android.jar: $ANDROID_JAR"
note "构建 $VN ($VC)，站点 $ORIGIN"

rm -rf "$BUILD" "$DIST"
mkdir -p "$BUILD/gen/com/forband/app" "$BUILD/classes" "$BUILD/dex" "$DIST"

cat > "$BUILD/gen/com/forband/app/BuildConfig.java" <<EOF
package com.forband.app;

public final class BuildConfig {
    public static final String SITE_ORIGIN = "$ORIGIN";
    public static final int VERSION_CODE = $VC;
    public static final String VERSION_NAME = "$VN";

    private BuildConfig() {
    }
}
EOF

note "生成图标"
python "$ROOT/tools/make-icons.py"

note "编译资源"
"$AAPT2" compile --dir "$ROOT/res" -o "$BUILD/res.zip"

note "链接资源"
"$AAPT2" link \
    -o "$BUILD/base.apk" \
    -I "$ANDROID_JAR" \
    --manifest "$ROOT/AndroidManifest.xml" \
    --java "$BUILD/gen" \
    --min-sdk-version "$MIN_SDK" \
    --target-sdk-version "$TARGET_SDK" \
    --version-code "$VC" \
    --version-name "$VN" \
    "$BUILD/res.zip"

note "编译 Java"
find "$ROOT/src" "$BUILD/gen" -name '*.java' -print0 \
    | xargs -0 javac -encoding UTF-8 -source 11 -target 11 \
        -classpath "$ANDROID_JAR" -d "$BUILD/classes"

note "dex"
find "$BUILD/classes" -name '*.class' -print0 \
    | xargs -0 java -cp "$D8_JAR" com.android.tools.r8.D8 \
        --min-api "$MIN_SDK" --lib "$ANDROID_JAR" --release --output "$BUILD/dex"

[[ -f "$BUILD/dex/classes.dex" ]] || die "d8 没有产出 classes.dex"

note "注入 classes.dex"
python - "$BUILD/base.apk" "$BUILD/dex/classes.dex" <<'PY'
import os
import sys
import zipfile

apk, dex = sys.argv[1], sys.argv[2]
tmp = apk + ".tmp"
with zipfile.ZipFile(apk, "r") as src, zipfile.ZipFile(tmp, "w") as dst:
    for item in src.infolist():
        dst.writestr(item, src.read(item.filename))
    dst.write(dex, "classes.dex")
os.replace(tmp, apk)
PY

note "对齐"
"$ZIPALIGN" -p -f 4 "$BUILD/base.apk" "$BUILD/aligned.apk"

PROPS="$ROOT/keys/keystore.properties"
[[ -f "$PROPS" ]] || die "缺少 $PROPS，请先运行 ./android/make-release-key.sh"
prop() { grep -E "^$1=" "$PROPS" | head -n1 | cut -d= -f2-; }
STORE_FILE="$(prop storeFile)"
STORE_PASS="$(prop storePassword)"
KEY_ALIAS="$(prop keyAlias)"
KEY_PASS="$(prop keyPassword)"
[[ -f "$ROOT/keys/$STORE_FILE" ]] || die "缺少 keystore：$ROOT/keys/$STORE_FILE"

APK="$DIST/band-$VN.apk"
note "签名"
java -jar "$APKSIGNER_JAR" sign \
    --ks "$ROOT/keys/$STORE_FILE" \
    --ks-pass "pass:$STORE_PASS" \
    --key-pass "pass:$KEY_PASS" \
    --ks-key-alias "$KEY_ALIAS" \
    --out "$APK" \
    "$BUILD/aligned.apk"

note "校验签名"
java -jar "$APKSIGNER_JAR" verify --verbose --print-certs "$APK"

python - "$DIST/version.json" "$VC" "$VN" "$ORIGIN/app/band.apk" "$NOTES" <<'PY'
import json
import sys

path, vc, vn, apk, notes = sys.argv[1:6]
data = {
    "versionCode": int(vc),
    "versionName": vn,
    "apk": apk,
    "notes": notes,
}
with open(path, "w", encoding="utf-8") as f:
    json.dump(data, f, ensure_ascii=False, indent=2)
    f.write("\n")
PY

if [[ "${1:-}" == "--publish" ]]; then
    OUT="$REPO/src/main/resources/static/app"
    mkdir -p "$OUT"
    cp "$APK" "$OUT/band.apk"
    cp "$DIST/version.json" "$OUT/version.json"
    note "已发布到 $OUT"
fi

ls -lh "$APK" "$DIST/version.json"
echo
echo "完成：$APK"
