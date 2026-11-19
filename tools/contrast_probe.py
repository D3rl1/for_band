#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""#257 回归探针：跨页面文字颜色配对（可重复跑，失败退出码 1）。

跑法：python tools/contrast_probe.py
不需要后端：用 `tools/profile_navigation_fixture.py` 的隔离接口夹具。

覆盖四件事（与 docs/14 §14.6.2c C257-01..04 对应）：
- C257-01：四类已复现反例（节拍器术语、下一场日期、调音器参考频率、
            主按钮 hover）在 标准/中/高 × 日/夜 下的最终合成对比度；
- C257-02：`--ink-3` 不再当信息文字；强调文字走 `--accent-text`；
            页面级扫描同条件下没有低于门槛的正文/小字；
- C257-03：六预置种子 + 极浅/极深/自定义种子的令牌配对
            （accentText×surface/bg、accentInk×accentWeak）；
- C257-04：390 / 320px 下的页面级扫描（无 hover）。

证据（截图 + JSON）写到 gitignored 的 design-samples/shots-contrast-257/。
⚠️ 隔离浏览器 + 虚构数据；不代表真机字体渲染与屏幕观感。
"""
from __future__ import annotations

import json
import re
import sys
import threading
from pathlib import Path

sys.stdout.reconfigure(encoding='utf-8', errors='replace')

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / 'tools'))
import profile_navigation_fixture as fx  # noqa: E402
from playwright.sync_api import sync_playwright  # noqa: E402

STATIC = ROOT / 'src/main/resources/static'
OUT = ROOT / 'design-samples/shots-contrast-257'
OUT.mkdir(parents=True, exist_ok=True)

TOTAL = [0]
BAD: list[str] = []
REPORT: dict = {'targets': [], 'pageScans': [], 'tokens': [], 'pageErrors': []}


def check(name: str, ok: bool, extra=None) -> None:
    TOTAL[0] += 1
    print(('  OK   ' if ok else '  FAIL ') + name + ('' if extra is None else '   | ' + str(extra)))
    if not ok:
        BAD.append(name)


def section(title: str) -> None:
    print('\n' + '=' * 76 + '\n' + title + '\n' + '=' * 76)


# ---------------------------------------------------------------- 量测脚本
MEASURE = r"""(sel) => {
  const el = document.querySelector(sel);
  if (!el) return {found: false};
  const s = getComputedStyle(el);
  const rect = el.getBoundingClientRect();
  const cv = document.createElement('canvas'); cv.width = cv.height = 1;
  const cx = cv.getContext('2d', {willReadFrequently: true});
  const rgba = (v) => {
    cx.clearRect(0, 0, 1, 1); cx.fillStyle = v; cx.fillRect(0, 0, 1, 1);
    const d = cx.getImageData(0, 0, 1, 1).data;
    return [d[0], d[1], d[2], d[3] / 255];
  };
  const over = (f, b) => [0, 1, 2].map(i => f[i] * f[3] + b[i] * (1 - f[3])).concat(1);
  const lum = (c) => {
    const a = c.slice(0, 3).map(n => { n /= 255; return n <= .04045 ? n / 12.92 : ((n + .055) / 1.055) ** 2.4; });
    return .2126 * a[0] + .7152 * a[1] + .0722 * a[2];
  };
  let p = el, layers = [], opacity = 1, complex = false, opaque = false;
  while (p) {
    const a = getComputedStyle(p);
    if (a.display === 'none' || a.visibility !== 'visible') { opacity = 0; break; }
    opacity *= +a.opacity;
    if (!opaque) {
      if (a.backgroundImage !== 'none' || a.mixBlendMode !== 'normal' || a.filter !== 'none') complex = true;
      const f = rgba(a.backgroundColor);
      layers.push(f);
      if (f[3] === 1) opaque = true;
    }
    p = p.parentElement;
  }
  let bg = [255, 255, 255, 1];
  for (let i = layers.length - 1; i >= 0; i--) bg = over(layers[i], bg);
  let fg = rgba(s.webkitTextFillColor && s.webkitTextFillColor !== 'currentcolor' ? s.webkitTextFillColor : s.color);
  fg = over(fg, bg);
  const ratio = (Math.max(lum(fg), lum(bg)) + .05) / (Math.min(lum(fg), lum(bg)) + .05);
  const fs = parseFloat(s.fontSize);
  const threshold = (fs >= 24 || (fs >= 18.66 && +s.fontWeight >= 700)) ? 3 : 4.5;
  return {
    found: true, text: (el.textContent || '').trim().slice(0, 60),
    color: s.color, textFill: s.webkitTextFillColor,
    bg: bg.slice(0, 3).map(Math.round), opacity: +opacity.toFixed(2), complex,
    ratio: +ratio.toFixed(3), threshold, fontSize: s.fontSize,
    visible: rect.width > 0 && rect.height > 0 && s.visibility === 'visible'
  };
}"""

PAGE_SCAN = r"""() => {
  const cv = document.createElement('canvas'); cv.width = cv.height = 1;
  const cx = cv.getContext('2d', {willReadFrequently: true});
  const rgba = (v) => {
    cx.clearRect(0, 0, 1, 1); cx.fillStyle = v; cx.fillRect(0, 0, 1, 1);
    const d = cx.getImageData(0, 0, 1, 1).data;
    return [d[0], d[1], d[2], d[3] / 255];
  };
  const over = (f, b) => [0, 1, 2].map(i => f[i] * f[3] + b[i] * (1 - f[3])).concat(1);
  const lum = (c) => {
    const a = c.slice(0, 3).map(n => { n /= 255; return n <= .04045 ? n / 12.92 : ((n + .055) / 1.055) ** 2.4; });
    return .2126 * a[0] + .7152 * a[1] + .0722 * a[2];
  };
  const seen = new Set(), bad = [], all = [];
  let uncertain = 0;
  const walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT);
  let node;
  while ((node = walker.nextNode())) {
    const text = node.textContent.trim();
    if (!text || !node.parentElement) continue;
    const el = node.parentElement;
    if (seen.has(el) || el.closest('script,style,svg,option,[hidden],[disabled],.sr-only')) continue;
    seen.add(el);
    const rect = el.getBoundingClientRect(), s = getComputedStyle(el);
    if (!rect.width || !rect.height || s.visibility !== 'visible' || +s.fontSize < 10) continue;
    let p = el, layers = [], complex = false, opacity = 1, opaque = false;
    while (p) {
      const a = getComputedStyle(p);
      if (a.display === 'none' || a.visibility !== 'visible') { opacity = 0; break; }
      opacity *= +a.opacity;
      if (!opaque) {
        if (a.backgroundImage !== 'none' || a.mixBlendMode !== 'normal' || a.filter !== 'none') complex = true;
        const f = rgba(a.backgroundColor);
        layers.push(f);
        if (f[3] === 1) opaque = true;
      }
      p = p.parentElement;
    }
    if (complex || opacity < .99) { uncertain++; continue; }
    let bg = [255, 255, 255, 1];
    for (let i = layers.length - 1; i >= 0; i--) bg = over(layers[i], bg);
    let fg = rgba(s.webkitTextFillColor === 'currentcolor' ? s.color : s.webkitTextFillColor || s.color);
    fg = over(fg, bg);
    const ratio = (Math.max(lum(fg), lum(bg)) + .05) / (Math.min(lum(fg), lum(bg)) + .05);
    const fs = parseFloat(s.fontSize);
    const threshold = (fs >= 24 || (fs >= 18.66 && +s.fontWeight >= 700)) ? 3 : 4.5;
    const item = {
      selector: el.id ? '#' + el.id : el.tagName.toLowerCase() + '.' + [...el.classList].join('.'),
      text: text.slice(0, 70), color: s.color, bg: bg.slice(0, 3).map(Math.round),
      ratio: +ratio.toFixed(3), threshold, fontSize: s.fontSize
    };
    all.push(item);
    if (ratio < threshold) bad.push(item);
  }
  bad.sort((a, b) => a.ratio - b.ratio);
  return {bad, measured: all.length, uncertain};
}"""


def api(route) -> None:
    from urllib.parse import urlsplit
    path = urlsplit(route.request.url).path
    if path == '/api/events':
        fx.respond(route, {'list': [{
            'id': 1, 'title': '隔离排练', 'date': '2099-11-13', 'startTime': '19:00:00',
            'endTime': '21:00:00', 'location': '隔离排练厅', 'kind': 'REHEARSAL', 'status': 'PLANNED'}], 'total': 1})
        return
    if path == '/api/concerts':
        fx.respond(route, {'list': [{
            'id': 1, 'name': '隔离演出', 'date': '2099-11-13', 'startTime': '19:00:00',
            'location': '隔离音乐厅', 'status': 'PLANNED', 'pieceCount': 1, 'castCount': 1,
            'photoCount': 0}], 'total': 1})
        return
    if path == '/api/leave-requests':
        fx.respond(route, {'list': [], 'total': 2})
        return
    fx.api(route)


def apply_theme(page, base: str, level: str, mode: str, seed: str = '#1F6F5C') -> None:
    page.goto(base + '/theme.html')
    page.wait_for_selector('#theme-hex')
    page.fill('#theme-hex', seed)
    page.locator('#theme-hex').dispatch_event('change')
    page.locator(f'label:has(input[name="contrast"][value="{level}"])').click()
    if page.locator('#theme-apply').is_enabled():
        page.locator('#theme-apply').click()
    page.evaluate('(m) => localStorage.setItem("band.theme", m)', mode)
    cfg = page.evaluate('JSON.parse(localStorage.getItem("band.theme.settings.v2"))')
    assert cfg and cfg['contrast'] == level


# ---------------------------------------------------------------- 静态规则
def static_checks() -> None:
    section('A. 静态规则：令牌语义（C257-02）')
    css = (STATIC / 'band.css').read_text(encoding='utf-8')
    theme = (STATIC / 'theme.js').read_text(encoding='utf-8')
    nav = (STATIC / 'band-nav.js').read_text(encoding='utf-8')
    core = (STATIC / 'band-core.js').read_text(encoding='utf-8')

    ink3_text = re.findall(r'color\s*:\s*var\(--ink-3\)', css)
    check('band.css 无 color:var(--ink-3) 文本消费者', not ink3_text, len(ink3_text))
    check('--accent-text 在 :root 有默认值', re.search(r'--accent-text\s*:', css) is not None)
    check('theme.js 把 --accent-text 映射为 primary',
          re.search(r"tokens\['--accent-text'\]\s*=\s*roles\.primary", theme) is not None)
    check('band-nav.js 首帧白名单含 accent-text', 'accent-text$' in nav)
    check('band-core.js APP_VARS 含 --accent-text', "'--accent-text'" in core)

    anchors = {
        '节拍器术语 `.mt-read .term`': r'\.mt-read \.term\{[^}]*color:\s*var\(--accent-text\)',
        '下一场日期 `.cn-next .datebox .d`': r'\.cn-next \.datebox \.d\{[^}]*color:\s*var\(--accent-text\)',
        '调音器频率 `.tn-a4 b`': r'\.tn-a4 b\s*\{[^}]*color:\s*var\(--accent-text\)',
        '候选按钮 `.ct-cand .go`': r'\.ct-cand \.go[^{]*\{[^}]*color:\s*var\(--onwood\)',
    }
    for label, pattern in anchors.items():
        check(f'{label} 已迁移', re.search(pattern, css) is not None)

    hovers = {
        '主按钮 `.ev-btn-primary:hover`': r'\.ev-btn-primary:hover\s*\{[^}]*color-mix\([^}]*var\(--ink\)',
        '录音主按钮 `.rc-btn-primary:hover`': r'\.rc-btn-primary:hover\s*\{[^}]*color-mix\([^}]*var\(--ink\)',
        '活动页 `.go:hover`': r'\.go:hover\s*\{[^}]*color-mix\([^}]*var\(--ink\)',
        'PWA 下载 `.pwa-dl:hover`': r'\.pwa-dl:hover\s*\{[^}]*color-mix\([^}]*var\(--ink\)',
    }
    for label, pattern in hovers.items():
        check(f'{label} hover 背景随文字一起核', re.search(pattern, css) is not None)


# ---------------------------------------------------------------- 四类反例
TARGETS = [
    ('events.html', 'normal', '#hero-go', '活动页主按钮 签到'),
    ('events.html', 'hover', '#hero-go', '活动页主按钮 签到 hover'),
    ('recordings.html', 'panel', '#m-term', '录音页节拍器术语'),
    ('recordings.html', 'panel', '#m-sig', '录音页节拍器拍号'),
    ('concerts.html', 'normal', '.cn-next .datebox .d', '下一场演出 日期数字'),
    ('concerts.html', 'normal', '.cn-next .n-lab', '下一场演出 标签'),
    ('concerts.html', 'normal', '.cn-next .n-count', '下一场演出 倒数'),
    ('concerts.html', 'normal', '.cn-next .datebox .w', '下一场演出 月份'),
    ('tuner.html', 'normal', '.tn-a4 b', '调音器 参考频率'),
]


def _rgb(text: str):
    nums = re.findall(r'[\d.]+', text or '')
    return tuple(float(x) for x in nums[:3]) if len(nums) >= 3 else None


def _lum(c) -> float:
    def lin(x):
        x /= 255.0
        return x / 12.92 if x <= 0.04045 else ((x + 0.055) / 1.055) ** 2.4
    r, g, b = (lin(x) for x in c)
    return 0.2126 * r + 0.7152 * g + 0.0722 * b


def _ratio(fg, bg) -> float:
    return (max(_lum(fg), _lum(bg)) + 0.05) / (min(_lum(fg), _lum(bg)) + 0.05)


def measure(page, sel: str) -> dict:
    """计算样式优先；渐变 / 图像底则把文字临时置透明后取实际像素。"""
    info = page.evaluate(MEASURE, sel)
    if not info['found'] or not info['complex']:
        return info
    try:
        from io import BytesIO
        from PIL import Image
    except ImportError:  # 没有 Pillow 就如实退回「需人工核验」，不装模作样给数
        info['sampledRatio'] = None
        return info
    old = page.evaluate(
        "(sel) => { const el = document.querySelector(sel); const v = el.style.color;"
        " el.style.webkitTextFillColor = ''; el.style.color = 'transparent'; return v; }", sel)
    page.wait_for_timeout(80)
    shot = page.locator(sel).screenshot()
    page.evaluate("(a) => { const el = document.querySelector(a[0]); el.style.color = a[1] || ''; }",
                  [sel, old])
    img = Image.open(BytesIO(shot)).convert('RGB')
    fg = _rgb(info['color'])
    worst = None
    for fy in (0.25, 0.5, 0.75):
        y = min(img.height - 1, max(0, int(img.height * fy)))
        for fx in (0.25, 0.5, 0.75):
            x = min(img.width - 1, max(0, int(img.width * fx)))
            r = _ratio(fg, img.getpixel((x, y)))
            worst = r if worst is None else min(worst, r)
    info['sampledRatio'] = None if worst is None else round(worst, 3)
    info['sampledBg'] = 'pixel'
    return info


def open_state(page, base: str, page_name: str, state: str) -> None:
    page.goto(base + '/' + page_name)
    page.wait_for_timeout(650)
    page.evaluate('document.fonts.ready')
    if page_name == 'recordings.html' and state == 'panel':
        page.locator('#go-record').click()
        page.wait_for_timeout(200)


def run_targets_and_scans(page, base: str) -> None:
    section('B. 四类反例 × 标准/中/高 × 日/夜（C257-01）')
    for level in ('standard', 'medium', 'high'):
        for mode in ('light', 'dark'):
            apply_theme(page, base, level, mode)
            tag = f'{level}/{mode}'
            for page_name, state, sel, label in TARGETS:
                open_state(page, base, page_name, state)
                if state == 'hover':
                    page.hover(sel)
                    page.wait_for_timeout(250)
                m = measure(page, sel)
                assert m['found'], f'{tag} {sel} 不存在'
                used = m.get('sampledRatio') if m['complex'] else m['ratio']
                ok = (m['visible'] and m['opacity'] >= .99
                      and used is not None and used >= m['threshold'])
                check(f'{tag} {label} ≥ {m["threshold"]}:1'
                      + ('（像素取样）' if m['complex'] else ''), ok, m)
                REPORT['targets'].append({'level': level, 'mode': mode, 'page': page_name,
                                          'state': state, 'selector': sel, 'measure': m})
            # 页面级：同一状态、同一条件扫全页
            for page_name, state in (('events.html', 'normal'), ('recordings.html', 'panel'),
                                     ('concerts.html', 'normal'), ('tuner.html', 'normal')):
                open_state(page, base, page_name, state)
                scan = page.evaluate(PAGE_SCAN)
                check(f'{tag} {page_name} 页面扫描 0 处低于门槛', not scan['bad'],
                      {'measured': scan['measured'], 'uncertain': scan['uncertain'],
                       'bad': scan['bad'][:5]})
                REPORT['pageScans'].append({'level': level, 'mode': mode, 'page': page_name,
                                            'state': state, **{k: scan[k] for k in ('measured', 'uncertain')},
                                            'bad': scan['bad']})
                page.screenshot(path=str(OUT / f'{page_name[:-5]}-{tag.replace("/", "-")}.png'))


# ---------------------------------------------------------------- 令牌 × 种子
SEEDS = [
    ('jade', '#1F6F5C'), ('teal', '#1C6E77'), ('violet', '#6A4A9C'),
    ('olive', '#5F6A2C'), ('clay', '#A45A34'), ('ink', '#1C1F21'),
    ('pale', '#FEFEFE'), ('deep', '#050505'), ('custom', '#B23A2E'),
]
TOKEN_MEASURE = r"""() => {
  const pv = document.querySelector('.theme-preview');
  const s = getComputedStyle(pv);
  const val = (k) => s.getPropertyValue(k).trim();
  return {
    accentText: val('--accent-text'), surface: val('--surface'), surface2: val('--surface-2'),
    bg: val('--bg'), accentInk: val('--accent-ink'), accentWeak: val('--accent-weak')
  };
}"""
TOKEN_RATIO = r"""() => {
  const pv = document.querySelector('.theme-preview');
  const s = getComputedStyle(pv);
  const cv = document.createElement('canvas'); cv.width = cv.height = 1;
  const cx = cv.getContext('2d', {willReadFrequently: true});
  const rgb = (v) => {
    cx.clearRect(0, 0, 1, 1); cx.fillStyle = v; cx.fillRect(0, 0, 1, 1);
    const d = cx.getImageData(0, 0, 1, 1).data; return [d[0], d[1], d[2]];
  };
  const lum = (c) => {
    const a = c.map(n => { n /= 255; return n <= .04045 ? n / 12.92 : ((n + .055) / 1.055) ** 2.4; });
    return .2126 * a[0] + .7152 * a[1] + .0722 * a[2];
  };
  const ratio = (a, b) => {
    const f = rgb(a), g = rgb(b);
    return +((Math.max(lum(f), lum(g)) + .05) / (Math.min(lum(f), lum(g)) + .05)).toFixed(3);
  };
  const v = (k) => s.getPropertyValue(k).trim();
  return {
    accentText_surface: ratio(v('--accent-text'), v('--surface')),
    accentText_surface2: ratio(v('--accent-text'), v('--surface-2')),
    accentText_bg: ratio(v('--accent-text'), v('--bg')),
    accentInk_accentWeak: ratio(v('--accent-ink'), v('--accent-weak'))
  };
}"""


def set_mode(page, mode: str) -> None:
    """用页面自己的日夜按钮切换：theme.js 的 `theme` 变量与预览重建都挂在它上面。"""
    if page.evaluate('document.documentElement.dataset.theme') == mode:
        return
    page.click('#night-btn')
    page.wait_for_function('(m) => document.documentElement.dataset.theme === m', arg=mode, timeout=4000)
    page.wait_for_timeout(260)


def run_token_matrix(page, base: str) -> None:
    section('C. 六预置 + 极浅/极深/自定义种子 × 三档 × 日/夜：令牌配对（C257-03）')
    page.goto(base + '/theme.html')
    page.wait_for_selector('#theme-hex')
    for name, seed in SEEDS:
        if name in ('jade', 'teal', 'violet', 'olive', 'clay', 'ink'):
            page.locator(f'.theme-seed[data-p="{name}"]').click()
            page.wait_for_timeout(160)
            check(f'预置 {name} 写入种子输入', page.input_value('#theme-hex').lower() == seed.lower(),
                  page.input_value('#theme-hex'))
        else:
            page.fill('#theme-hex', seed)
            page.locator('#theme-hex').dispatch_event('change')
            page.wait_for_timeout(160)
        for mode in ('light', 'dark'):
            set_mode(page, mode)
            for level in ('standard', 'medium', 'high'):
                page.locator(f'label:has(input[name="contrast"][value="{level}"])').click()
                page.wait_for_timeout(160)
                vals = page.evaluate(TOKEN_MEASURE)
                r = page.evaluate(TOKEN_RATIO)
                tag = f'{name}/{mode}/{level}'
                check(f'{tag} accentText×surface ≥ 4.5', r['accentText_surface'] >= 4.49, r)
                check(f'{tag} accentText×surface-2 ≥ 4.5', r['accentText_surface2'] >= 4.49, r)
                check(f'{tag} accentText×bg ≥ 4.5', r['accentText_bg'] >= 4.49, r)
                check(f'{tag} accentInk×accentWeak ≥ 4.5', r['accentInk_accentWeak'] >= 4.49, r)
                REPORT['tokens'].append({'seed': name, 'mode': mode, 'level': level,
                                         'vars': vals, 'ratios': r})
    set_mode(page, 'light')


# ---------------------------------------------------------------- 390 / 320
def run_mobile(page, base: str) -> None:
    section('D. 390 / 320px 页面级扫描（C257-04）')
    apply_theme(page, base, 'medium', 'light')
    for width, height in ((390, 844), (320, 720)):
        page.set_viewport_size({'width': width, 'height': height})
        for mode in ('light', 'dark'):
            page.evaluate('(m) => localStorage.setItem("band.theme", m)', mode)
            for page_name in ('events.html', 'concerts.html', 'tuner.html'):
                page.goto(base + '/' + page_name)
                page.wait_for_timeout(650)
                scan = page.evaluate(PAGE_SCAN)
                check(f'{width}px {mode} {page_name} 扫描 0 处低于门槛', not scan['bad'],
                      {'measured': scan['measured'], 'uncertain': scan['uncertain'],
                       'bad': scan['bad'][:5]})
                REPORT['pageScans'].append({'width': width, 'level': 'medium', 'mode': mode,
                                            'page': page_name, 'state': 'initial',
                                            **{k: scan[k] for k in ('measured', 'uncertain')},
                                            'bad': scan['bad']})


def main() -> int:
    static_checks()
    server = fx.ThreadingHTTPServer(('127.0.0.1', 0), fx.Handler)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    base = f'http://127.0.0.1:{server.server_port}'
    try:
        with sync_playwright() as pw:
            browser = pw.chromium.launch()
            ctx = browser.new_context(viewport={'width': 1280, 'height': 1000}, service_workers='block')
            ctx.add_init_script(
                "localStorage.setItem('band_token','isolated-fixture');"
                "sessionStorage.removeItem('band_welcome')")
            ctx.route('**/api/**', api)
            page = ctx.new_page()
            page.on('pageerror', lambda e: REPORT['pageErrors'].append(str(e)))
            page.on('console', lambda m: REPORT['pageErrors'].append('console:' + m.text)
                    if m.type == 'error' else None)
            run_targets_and_scans(page, base)
            run_token_matrix(page, base)
            run_mobile(page, base)
            check('全程无 JS 报错', not REPORT['pageErrors'], REPORT['pageErrors'][:5])
            browser.close()
    finally:
        server.shutdown()

    (OUT / 'contrast-257-audit.json').write_text(
        json.dumps(REPORT, ensure_ascii=False, indent=2), encoding='utf-8')
    print(f'\n合计 {TOTAL[0] - len(BAD)}/{TOTAL[0]} 通过；失败：{BAD or "无"}')
    print(f'证据：{OUT}')
    return 1 if BAD else 0


if __name__ == '__main__':
    raise SystemExit(main())
