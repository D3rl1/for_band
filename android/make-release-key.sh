#!/usr/bin/env bash
# 只跑一次：生成 release 签名钥匙。丢了就无法覆盖升级，务必离线备份 keys/。
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
KEYS="$ROOT/keys"
KEYSTORE="$KEYS/forband-release.p12"
PROPS="$KEYS/keystore.properties"
ALIAS="forband"

if [[ -f "$KEYSTORE" ]]; then
    echo "钥匙已存在：$KEYSTORE" >&2
    echo "不要覆盖：换签名后，已安装用户必须卸载重装，本地登录状态会丢。" >&2
    exit 1
fi

umask 077
mkdir -p "$KEYS"
PASS="$(python -c 'import secrets; print(secrets.token_urlsafe(24))')"

keytool -genkeypair \
    -keystore "$KEYSTORE" \
    -storetype PKCS12 \
    -storepass "$PASS" \
    -keypass "$PASS" \
    -alias "$ALIAS" \
    -keyalg RSA \
    -keysize 2048 \
    -validity 10000 \
    -dname "CN=Shanshui Band, OU=ForBand, O=Shanshui, L=Beijing, ST=Beijing, C=CN"

cat > "$PROPS" <<EOF
storeFile=forband-release.p12
storePassword=$PASS
keyAlias=$ALIAS
keyPassword=$PASS
EOF

chmod 600 "$KEYSTORE" "$PROPS" 2>/dev/null || true

echo "已生成："
echo "  $KEYSTORE"
echo "  $PROPS"
echo
echo "⚠️  立刻把 keys/ 整个目录离线备份（U 盘 / 密码管理器）。"
echo "   钥匙丢失 = 以后无法覆盖升级；首版签名必须一直沿用。"
