#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""从网页端 512 母图生成 Android 五档启动图标。

⚠️ 母图必须是 **`icons/pwa-rounded-261/icon-512.png`**（#252 新图 + #261 24% 圆角）。
老的 `icons/icon-512.png` 是 2026-10-19 的旧深绿波形图，`docs/19` §:47 明确写着
新图「不得再从它导出」；但本脚本一直指着它，于是 2026-11-16 换 PWA 图标时
**网页端变了、装到手机上的 APK 没变**——这条是 2026-11-17 用户发现的。
"""
import os
from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
SRC = os.path.join(ROOT, "..", "src", "main", "resources", "static", "icons",
                   "pwa-rounded-261", "icon-512.png")
SIZES = {
    "mipmap-mdpi": 48,
    "mipmap-hdpi": 72,
    "mipmap-xhdpi": 96,
    "mipmap-xxhdpi": 144,
    "mipmap-xxxhdpi": 192,
}


def main():
    src = Image.open(SRC).convert("RGBA")
    if src.size != (512, 512):
        raise SystemExit("icon-512.png 不是 512x512：%r" % (src.size,))
    for folder, size in SIZES.items():
        out_dir = os.path.join(ROOT, "res", folder)
        os.makedirs(out_dir, exist_ok=True)
        icon = src.resize((size, size), Image.LANCZOS)
        icon.save(os.path.join(out_dir, "ic_launcher.png"), "PNG", optimize=True)
        print("%s -> %dx%d" % (folder, size, size))


if __name__ == "__main__":
    main()
