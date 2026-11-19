"""#252 PWA 图标导出：从用户选定原图生成五张 PNG（可重复执行）。

规格：docs/19 §19.3.2a。
- 唯一来源：docs/assets/pwa-icon-252/selected-source.jpg（JPEG/RGB 1254×1254，
  SHA256 5043a7da…a4f97）。不得改用旧 SVG 或重新生成相似图。
- 普通图标：整图等比缩放，保留原构图 / 白底 / 红色主体，不拉伸、不加字、不烘焙圆角。
- maskable：整图等比缩进「边长 40% 安全圆」内，实心浅底铺满 512，不裁掉主体。
  512 的正中安全圆半径 = 204.8px；整图等比缩到 288×288 时，其四角到圆心距离
  288/2*√2 ≈ 203.6px < 204.8px ⇒ 所有内容都在安全圆内。
- 全部输出 RGB（无透明通道）、精确尺寸。

用法：python tools/make_pwa_icons_252.py [--preview]
"""
from __future__ import annotations

import hashlib
import json
import sys
from pathlib import Path

from PIL import Image, ImageDraw

if hasattr(sys.stdout, 'reconfigure'):
    sys.stdout.reconfigure(encoding='utf-8')

ROOT = Path(__file__).resolve().parent.parent
SRC = ROOT / 'docs/assets/pwa-icon-252/selected-source.jpg'
OUT_DIR = ROOT / 'src/main/resources/static/icons/pwa-252'
PREVIEW_DIR = ROOT / 'design-samples/shots-pwa-252'

SRC_SHA256 = '5043a7da3b8b62b7a9b8a75e90cbcbf65543d81431438be9f18b223e176a4f97'
TARGETS = {
    'icon-192.png': 192,
    'icon-512.png': 512,
    'icon-maskable-512.png': 512,
    'apple-touch-icon-180.png': 180,
    'favicon-32.png': 32,
}
SAFE_RADIUS = 512 * 0.40          # 204.8px：maskable 安全圆半径
SAFE_MARGIN = 0.98                # 留 2% 余量，避免主体贴边


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def save_no_alpha(im: Image.Image, path: Path) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    im.convert('RGB').save(path, 'PNG', optimize=True)


def corner_fill(src: Image.Image) -> tuple[int, int, int]:
    """取四角均值当实心铺底色：原图白 / 浅灰底，遮罩区与图内底色一致。"""
    w, h = src.size
    pts = [src.getpixel((1, 1)), src.getpixel((w - 2, 1)),
           src.getpixel((1, h - 2)), src.getpixel((w - 2, h - 2))]
    return tuple(round(sum(c[i] for c in pts) / len(pts)) for i in range(3))


def content_bbox(src: Image.Image, fill: tuple[int, int, int], threshold: int = 30):
    """红色主体 / 山景的包围盒：与四角底色差超过阈值的像素。

    阈值 30 排除原图很淡的圆角阴影（差值约 12–20），只把真正的笔画 / 山景
    当「需保留的主构图」；阴影落在安全圆外时被遮罩裁掉，不属于内容裁断。
    """
    l, t = src.size[0], src.size[1]
    r = b = 0
    for y in range(src.size[1]):
        for x in range(src.size[0]):
            px = src.getpixel((x, y))
            if max(abs(px[i] - fill[i]) for i in range(3)) > threshold:
                if x < l: l = x
                if x > r: r = x
                if y < t: t = y
                if y > b: b = y
    return (l, t, r, b)


def make_maskable(src: Image.Image):
    """整图等比缩放到「内容包围盒刚好装进安全圆」，实心浅底铺满 512。

    外围白边可以落在安全圆外（遮罩会裁掉它，不影响内容）；红色笔画、乐器顶部与
    山景全部在安全圆内。返回 (图, 测量字典) 供回执记录。
    """
    fill = corner_fill(src)
    l, t, r, b = content_bbox(src, fill)
    w, h = src.size
    cx, cy = (w - 1) / 2, (h - 1) / 2
    # 包围盒四角相对原图中心的最大距离（缩放前）
    far = max(((x - cx) ** 2 + (y - cy) ** 2) ** .5
              for x in (l, r) for y in (t, b))
    scale = SAFE_RADIUS * SAFE_MARGIN / far
    size = int(round(w * scale))
    canvas = Image.new('RGB', (512, 512), fill)
    small = src.resize((size, size), Image.LANCZOS)
    off = (512 - size) // 2
    canvas.paste(small, (off, off))
    measure = {
        'content_bbox': [l, t, r, b],
        'scaled_size': size,
        'content_radius_after_scale': round(far * scale, 1),
        'safe_radius': SAFE_RADIUS,
    }
    return canvas, measure


def masked_preview(im: Image.Image, kind: str) -> Image.Image:
    """预览用：给图标套圆 / 圆角方 / 方遮罩（预览可以带透明）。"""
    size = im.size[0]
    mask = Image.new('L', (size, size), 0)
    d = ImageDraw.Draw(mask)
    if kind == 'circle':
        d.ellipse((0, 0, size - 1, size - 1), fill=255)
    elif kind == 'rounded':
        d.rounded_rectangle((0, 0, size - 1, size - 1), radius=round(size * .22), fill=255)
    else:
        d.rectangle((0, 0, size - 1, size - 1), fill=255)
    out = im.convert('RGBA')
    out.putalpha(mask)
    return out


def contact_sheet(out: dict[str, Image.Image]) -> Image.Image:
    """给用户点验的对照图：普通 512、maskable × 三种遮罩、小尺寸。"""
    W, H = 1000, 700
    sheet = Image.new('RGB', (W, H), (247, 243, 236))
    d = ImageDraw.Draw(sheet)
    sheet.paste(out['icon-512.png'].resize((256, 256), Image.LANCZOS), (30, 50))
    d.text((30, 22), 'any 512×512', fill=(19, 33, 29))
    x = 320
    for kind, label in [('circle', 'circle'), ('rounded', 'rounded'), ('square', 'square')]:
        pv = masked_preview(out['icon-maskable-512.png'], kind).resize((170, 170), Image.LANCZOS)
        bg = Image.new('RGBA', pv.size, (19, 33, 29, 255))
        bg.alpha_composite(pv)
        sheet.paste(bg.convert('RGB'), (x, 50))
        d.text((x, 22), 'maskable · ' + label, fill=(19, 33, 29))
        x += 220
    y = 280
    for i, name in enumerate(('icon-192.png', 'apple-touch-icon-180.png', 'favicon-32.png')):
        shown = out[name].resize((112, 112), Image.LANCZOS)
        sheet.paste(shown, (30 + i * 240, y))
        d.text((30 + i * 240, y + 122), f'{name}', fill=(19, 33, 29))
    d.text((30, 470), 'maskable 内容包围盒已贴安全圆：外围白边会被圆形遮罩裁掉，红色主体与山景完整保留。',
           fill=(60, 70, 66))
    return sheet


def main() -> int:
    raw = SRC.read_bytes()
    got = hashlib.sha256(raw).hexdigest()
    if got != SRC_SHA256:
        print(f'FAIL 原图哈希变了：{got}')
        return 1
    src = Image.open(SRC)
    if src.mode != 'RGB' or src.size != (1254, 1254):
        print(f'FAIL 原图属性不符：{src.mode} {src.size}')
        return 1

    out: dict[str, Image.Image] = {}
    measure = None
    for name, size in TARGETS.items():
        if name == 'icon-maskable-512.png':
            im, measure = make_maskable(src)
        else:
            im = src.resize((size, size), Image.LANCZOS)
        out[name] = im
        save_no_alpha(im, OUT_DIR / name)

    lines = [
        '# PWA 图标 #252 · 生产导出',
        '',
        '- 原图：`docs/assets/pwa-icon-252/selected-source.jpg`，已核对 SHA256 与规格一致。',
        '- 生成脚本：`tools/make_pwa_icons_252.py`（加 `--preview` 出对照图）。',
        f"- maskable：整图等比缩到 {measure['scaled_size']}×{measure['scaled_size']} 居中、实心浅底铺满；"
        f"内容包围盒 {measure['content_bbox']} 缩放后最远点距圆心 "
        f"{measure['content_radius_after_scale']}px < 安全圆半径 {measure['safe_radius']}px。",
        '- 全部 RGB（无透明）、精确尺寸；旧 `/icons/*` 保留不动。',
        '',
        '| 输出 | 尺寸 | SHA256 |',
        '|---|---|---|',
    ]
    hashes = {}
    for name in TARGETS:
        p = OUT_DIR / name
        with Image.open(p) as check:
            if check.mode != 'RGB' or check.size != (TARGETS[name], TARGETS[name]):
                print(f'FAIL 输出不符：{name} {check.mode} {check.size}')
                return 1
        hashes[name] = sha256(p)
        lines.append(f'| `{name}` | {TARGETS[name]}×{TARGETS[name]} | `{hashes[name]}` |')
    (OUT_DIR / 'SOURCE.md').write_text('\n'.join(lines) + '\n', encoding='utf-8')

    if '--preview' in sys.argv:
        PREVIEW_DIR.mkdir(parents=True, exist_ok=True)
        sheet = contact_sheet(out)
        sheet.save(PREVIEW_DIR / '样张-PWA图标-252.png')
        for kind in ('circle', 'rounded', 'square'):
            pv = masked_preview(out['icon-maskable-512.png'], kind)
            bg = Image.new('RGBA', pv.size, (19, 33, 29, 255))
            bg.alpha_composite(pv)
            bg.convert('RGB').save(PREVIEW_DIR / f'maskable-{kind}.png')
        print('预览：', PREVIEW_DIR / '样张-PWA图标-252.png')

    print(json.dumps({'source': SRC_SHA256, 'outputs': hashes}, ensure_ascii=False, indent=2))
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
