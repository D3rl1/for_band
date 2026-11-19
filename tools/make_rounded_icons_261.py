"""#261 桌面应用图标圆角：从 #252 两张普通图导出带透明圆角的 RGBA PNG。

规格：docs/19 §19.3.2b。
- 输入只认 `static/icons/pwa-252/icon-192.png` 与 `icon-512.png`（哈希核对）；
- 圆角半径 = 边长 24%（192 → 46.08px，512 → 122.88px）；
- 以 8 倍超采样绘制圆角矩形掩膜，再降采样得到平滑 alpha 边缘，
  保留小数精度；内部 alpha=255、四角 alpha=0；
- 仅改外轮廓 alpha，RGB 一个像素不动（不重绘、不缩放主体、不加底板 / 阴影）；
- maskable / Apple touch / favicon / 原生 APK 一律不碰。

用法：python tools/make_rounded_icons_261.py
可重复执行：同一 Pillow / 输入下输出字节一致。
"""
from __future__ import annotations

import hashlib
import sys
from pathlib import Path

from PIL import Image, ImageDraw

if hasattr(sys.stdout, 'reconfigure'):
    sys.stdout.reconfigure(encoding='utf-8')

ROOT = Path(__file__).resolve().parent.parent
IN_DIR = ROOT / 'src/main/resources/static/icons/pwa-252'
OUT_DIR = ROOT / 'src/main/resources/static/icons/pwa-rounded-261'

RADIUS_RATIO = 0.24
SUPERSAMPLE = 8
TARGETS = {'icon-192.png': 192, 'icon-512.png': 512}
EXPECTED_INPUT_SHA = {
    'icon-192.png': 'a06acc9852ff2663c60c68d7ba5a6be3bb56e8e16e0524f9ae623d33fe5728db',
    'icon-512.png': '5af66502337fff749daa0466f9776b7e8dd248a4e72ea143ce8291ec2c2059a5',
}


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def rounded_alpha(size: int) -> Image.Image:
    """边长 24% 的圆角矩形 alpha 掩膜（8× 超采样后降采样，边缘平滑）。"""
    n = size * SUPERSAMPLE
    mask = Image.new('L', (n, n), 0)
    ImageDraw.Draw(mask).rounded_rectangle(
        (0, 0, n - 1, n - 1), radius=size * RADIUS_RATIO * SUPERSAMPLE, fill=255)
    return mask.resize((size, size), Image.LANCZOS)


def main() -> int:
    for name, want in EXPECTED_INPUT_SHA.items():
        got = sha256(IN_DIR / name)
        if got != want:
            print(f'输入 {name} 哈希不符：{got} ≠ {want}', file=sys.stderr)
            return 1

    OUT_DIR.mkdir(parents=True, exist_ok=True)
    rows = []
    for name, size in TARGETS.items():
        with Image.open(IN_DIR / name) as src:
            src.load()
            if src.mode != 'RGB' or src.size != (size, size):
                print(f'输入 {name} 预期 RGB {size}×{size}，实际 {src.mode} {src.size}',
                      file=sys.stderr)
                return 1
            out = src.convert('RGBA')
        out.putalpha(rounded_alpha(size))
        dest = OUT_DIR / name
        out.save(dest, 'PNG', optimize=True)
        rows.append((name, size, sha256(dest)))
        print(f'{name}: {size}×{size} RGBA → {rows[-1][2]}')

    source_md = OUT_DIR / 'SOURCE.md'
    source_md.write_text(
        '# PWA 普通图标圆角 #261 · 生产导出\n\n'
        '- 输入（#252 普通图，未改动）：`src/main/resources/static/icons/pwa-252/`\n'
        '- 生成脚本：`tools/make_rounded_icons_261.py`（可重复执行，同一 Pillow / 输入下字节一致）\n'
        '- 圆角半径 = 边长 **24%**：192 → 46.08px，512 → 122.88px；8× 超采样掩膜后 LANCZOS 降采样，'
        '保留小数精度与平滑 alpha 边缘。\n'
        '- 仅改外轮廓 alpha：四角 alpha=0、内部 alpha=255，alpha=255 区域 RGB 与 #252 输入逐像素一致；'
        '不重绘 / 不缩放主体、不加底板或阴影。\n'
        '- maskable（`pwa-252/icon-maskable-512.png`）、Apple touch、favicon 与原生 APK 均未改动，'
        '旧资源保留兼容。\n\n'
        '| 输入 | 尺寸 | SHA256 |\n|---|---|---|\n'
        f'| `pwa-252/icon-192.png` | 192×192 | `{EXPECTED_INPUT_SHA["icon-192.png"]}` |\n'
        f'| `pwa-252/icon-512.png` | 512×512 | `{EXPECTED_INPUT_SHA["icon-512.png"]}` |\n\n'
        '| 输出 | 尺寸 | SHA256 |\n|---|---|---|\n'
        + ''.join(f'| `{name}` | {size}×{size} | `{digest}` |\n' for name, size, digest in rows)
        + '\n生成命令：`python tools/make_rounded_icons_261.py`\n'
        + '验收命令：`python tools/check_pwa_rounded_261.py`\n',
        encoding='utf-8')
    print(f'SOURCE.md 已写入 {source_md.relative_to(ROOT)}')
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
