#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""从网页端 512 母图生成 Android 五档启动图标。"""
import os
from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
SRC = os.path.join(ROOT, "..", "src", "main", "resources", "static", "icons", "icon-512.png")
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
