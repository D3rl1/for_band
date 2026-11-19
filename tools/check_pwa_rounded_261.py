"""#261 验收检查：桌面应用图标圆角（只读，不生成、不改文件）。

规格：docs/19 §19.3.2b。
- 输入必须是 #252 的两张普通图，哈希不变；
- 输出 `static/icons/pwa-rounded-261/icon-{192,512}.png`：精确尺寸、RGBA、
  四角 alpha=0、内部 alpha=255、边缘有抗锯齿过渡；
- alpha=255 区域与对应 #252 输入逐像素同 RGB；
- manifest 只有两条 purpose=any 指到新 URL，maskable / 身份字段不变；
- SW 预缓存含新两图且 VERSION 已顺延、旧两图仍在；
- SOURCE.md 记录的两张输出哈希与文件实际哈希一致。

用法：python tools/check_pwa_rounded_261.py
退出码 0 = 全过；1 = 有失败项。
"""
from __future__ import annotations

import hashlib
import json
import re
import sys
from pathlib import Path

from PIL import Image

if hasattr(sys.stdout, 'reconfigure'):
    sys.stdout.reconfigure(encoding='utf-8')

ROOT = Path(__file__).resolve().parent.parent
STATIC = ROOT / 'src/main/resources/static'
IN_DIR = STATIC / 'icons/pwa-252'
OUT_DIR = STATIC / 'icons/pwa-rounded-261'
MANIFEST = STATIC / 'manifest.json'
SW = STATIC / 'service-worker.js'
SOURCE = OUT_DIR / 'SOURCE.md'

INPUT_SHA = {
    'icon-192.png': 'a06acc9852ff2663c60c68d7ba5a6be3bb56e8e16e0524f9ae623d33fe5728db',
    'icon-512.png': '5af66502337fff749daa0466f9776b7e8dd248a4e72ea143ce8291ec2c2059a5',
}
RADIUS_RATIO = 0.24

checks: list[tuple[str, bool, str]] = []


def ck(name: str, ok: bool, detail: str = '') -> None:
    checks.append((name, bool(ok), detail))


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def check_inputs() -> None:
    for name, want in INPUT_SHA.items():
        p = IN_DIR / name
        ck(f'输入 {name} 存在', p.is_file())
        if p.is_file():
            ck(f'输入 {name} 哈希不变', sha256(p) == want, sha256(p))


def check_outputs() -> None:
    for size in (192, 512):
        name = f'icon-{size}.png'
        out = OUT_DIR / name
        ck(f'输出 {name} 存在', out.is_file())
        if not out.is_file():
            continue
        with Image.open(out) as im:
            im.load()
            ck(f'{name} 精确 {size}×{size}', im.size == (size, size), str(im.size))
            ck(f'{name} 为 RGBA', im.mode == 'RGBA', im.mode)
            if im.mode != 'RGBA':
                continue
            alpha = im.getchannel('A')
            px = alpha.load()
            w, h = im.size
            corners = [px[0, 0], px[w - 1, 0], px[0, h - 1], px[w - 1, h - 1]]
            ck(f'{name} 四角 alpha=0', corners == [0, 0, 0, 0], str(corners))
            # 内部取样：中心 50% 区域必须完全不透明（避开边缘）
            q = size // 4
            interior = all(px[x, y] == 255
                           for x in range(q, w - q, max(1, size // 16))
                           for y in range(q, h - q, max(1, size // 16)))
            ck(f'{name} 内部 alpha=255', interior)
            # 抗锯齿：边缘过渡像素（0 < alpha < 255）必须存在
            edge = sum(1 for x in range(w) for y in range(h)
                       if 0 < px[x, y] < 255)
            ck(f'{name} 边缘有抗锯齿过渡', edge > 0, f'过渡像素 {edge}')
            # RGB 保真：alpha=255 的像素与 #252 输入逐像素同 RGB
            with Image.open(IN_DIR / name) as src:
                src.load()
                sp = src.convert('RGB').load()
                op = im.load()
                diff = sum(1 for x in range(w) for y in range(h)
                           if op[x, y][3] == 255 and op[x, y][:3] != sp[x, y])
            ck(f'{name} 不透明区 RGB 与 #252 输入逐像素一致', diff == 0, f'差异 {diff}')


def check_source_md() -> None:
    ck('SOURCE.md 存在', SOURCE.is_file())
    if not SOURCE.is_file():
        return
    text = SOURCE.read_text(encoding='utf-8')
    ck('SOURCE.md 写明圆角 24%', bool(re.search(r'24\s*%', text)))
    ck('SOURCE.md 给出输入哈希', INPUT_SHA['icon-512.png'] in text)
    for size in (192, 512):
        name = f'icon-{size}.png'
        out = OUT_DIR / name
        if out.is_file():
            ck(f'SOURCE.md 记录 {name} 实际哈希', sha256(out) in text, sha256(out))
    ck('SOURCE.md 给出可重复生成命令',
       'tools/make_rounded_icons_261.py' in text)


def check_manifest() -> None:
    data = json.loads(MANIFEST.read_text(encoding='utf-8'))
    icons = data.get('icons', [])
    any_icons = [i for i in icons if i.get('purpose') == 'any']
    mask = [i for i in icons if i.get('purpose') == 'maskable']
    ck('manifest 恰有两条 any', len(any_icons) == 2, str(len(any_icons)))
    want = {
        192: '/icons/pwa-rounded-261/icon-192.png',
        512: '/icons/pwa-rounded-261/icon-512.png',
    }
    for i in any_icons:
        src = i.get('src', '')
        size = int(i.get('sizes', '0x0').split('x')[0] or 0)
        ck(f'any 图标 {src} 指到新目录且尺寸正确',
           want.get(size) == src and i.get('type') == 'image/png',
           f'{src} {i.get("sizes")}')
    ck('maskable 仍指 #252 原图',
       len(mask) == 1 and mask[0].get('src') == '/icons/pwa-252/icon-maskable-512.png')
    ck('身份字段未改', data.get('id') == '/' and data.get('start_url') == '/events.html'
       and data.get('scope') == '/' and data.get('display') == 'standalone'
       and data.get('name') == '山水民族管弦乐团')


def check_sw() -> None:
    text = SW.read_text(encoding='utf-8')
    m = re.search(r"const VERSION = 'v(\d+)';", text)
    ck('SW VERSION 已从 v8 顺延', bool(m) and int(m.group(1)) > 8,
       m.group(1) if m else '未找到')
    for url in ('/icons/pwa-rounded-261/icon-192.png',
                '/icons/pwa-rounded-261/icon-512.png'):
        ck(f'SW 预缓存含 {url}', url in text)
    for url in ('/icons/pwa-252/icon-192.png', '/icons/pwa-252/icon-512.png'):
        ck(f'SW 仍保留旧资源 {url}（兼容旧客户端）', url in text)


def main() -> int:
    check_inputs()
    check_outputs()
    check_source_md()
    check_manifest()
    check_sw()

    failed = [c for c in checks if not c[1]]
    for name, ok, detail in checks:
        print(('PASS ' if ok else 'FAIL ') + name + (f'  [{detail}]' if detail and not ok else ''))
    print(f'\n{len(checks) - len(failed)}/{len(checks)} 通过')
    return 1 if failed else 0


if __name__ == '__main__':
    raise SystemExit(main())
