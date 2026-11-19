#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""活动页 hero 夜间边界**生产探针**（2026-11-17 用户反馈“夜间 hero 边框淡到跟背景融为一体”）。

跑法：`python tools/events_dark_edge_probe.py`（不连数据库：静态页 + 接口路由桩）。
判据：浅色下 hero **不加边框**（深卡对浅底本身就分得开）；夜间 hero 有一圈 accent 细边，
且**截图实测**「边框像素 vs 页面底像素」亮度对比 ≥ 2.0 —— 只量计算样式不够，
因为边框是半透明叠在渐变上的，观感由最终像素决定。
第二个场景走**主题设置页真实应用**：生成令牌的夜间 `--hero-1` 曾等于 M3 夜间 `surface`
（都是 neutral tone 6）⇒ 卡面与页底同色，必须锁住（≥ 1.05）。
输出截图到 `design-samples/shots-dark/`（该目录已 gitignore，仅供本机查看）。
失败退出码 1。
"""
import io
import json
import sys
import threading
from pathlib import Path
from http.server import ThreadingHTTPServer
from urllib.parse import urlsplit

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / 'tools'))
sys.stdout.reconfigure(encoding='utf-8')
import profile_navigation_fixture as f
from playwright.sync_api import sync_playwright
from PIL import Image

SHOTS = ROOT / 'design-samples' / 'shots-dark'
TODAY = '2026-11-17'
EVENTS = [
    {'id': 1, 'title': '周五合排', 'kind': 'REHEARSAL', 'date': TODAY,
     'startTime': '19:00:00', 'endTime': '21:00:00', 'location': '排练厅', 'sectionName': None, 'status': 'NORMAL'},
]

CHECKS = []


def ck(name, ok, detail=None):
    CHECKS.append((name, bool(ok), detail))
    print(('OK  ' if ok else 'FAIL') + ' ' + name + ('' if detail is None else ' | ' + str(detail)))


def route_api(route):
    u = urlsplit(route.request.url)
    if u.path == '/api/events':
        f.respond(route, {'list': EVENTS, 'total': len(EVENTS)})
    else:
        f.api(route)


def lum(px):
    def ch(v):
        v /= 255.0
        return v / 12.92 if v <= 0.04045 else ((v + 0.055) / 1.055) ** 2.4
    r, g, b = (ch(x) for x in px[:3])
    return 0.2126 * r + 0.7152 * g + 0.0722 * b


def ratio(a, b):
    return (max(a, b) + 0.05) / (min(a, b) + 0.05)


def measure(p, tag):
    """截图量 hero 左边缘：外=页面底、edge=边框最高亮像素、内=卡身。"""
    p.screenshot(path=str(SHOTS / f'events-{tag}.png'), full_page=True)
    st = p.evaluate("""() => {
      const e = document.querySelector('.ev-hero');
      const c = getComputedStyle(e);
      const r = getComputedStyle(document.documentElement);
      return {bw: parseFloat(c.borderTopWidth), bc: c.borderTopColor,
              bg: getComputedStyle(document.body).backgroundColor,
              hero1: r.getPropertyValue('--hero-1').trim(),
              pageBg: r.getPropertyValue('--bg').trim()};
    }""")
    r = p.locator('.ev-hero').bounding_box()
    png = p.screenshot(clip={'x': r['x'] - 6, 'y': r['y'] + 60, 'width': 12, 'height': 1})
    row = [Image.open(io.BytesIO(png)).convert('RGB').getpixel((x, 0)) for x in range(12)]
    outer = lum(row[0])
    edge = max(lum(px) for px in row)
    edge_x = max(range(12), key=lambda i: lum(row[i]))
    return {'st': st, 'row': row, 'edge_x': edge_x,
            'edge_ratio': ratio(edge, outer), 'inner_ratio': ratio(lum(row[-1]), outer)}


def main():
    srv = ThreadingHTTPServer(('127.0.0.1', 0), f.Handler)
    threading.Thread(target=srv.serve_forever, daemon=True).start()
    base = f'http://127.0.0.1:{srv.server_address[1]}'
    SHOTS.mkdir(parents=True, exist_ok=True)
    with sync_playwright() as pw:
        browser = pw.chromium.launch(
            executable_path=r'C:\Program Files\Google\Chrome\Application\chrome.exe', headless=True)

        # ---------- 1. 未应用主题：默认 CSS 令牌 ----------
        for theme, palette in (('light', 'jade'), ('dark', 'jade'), ('dark', 'clay')):
            tag = f'{theme}-{palette}'
            ctx = browser.new_context(viewport={'width': 1280, 'height': 900}, service_workers='block')
            ctx.add_init_script(
                "localStorage.setItem('band_token','probe');"
                "sessionStorage.setItem('band.welcome.done','1');"
                f"localStorage.setItem('band.theme','{theme}');"
                f"localStorage.setItem('band.palette','{palette}');")
            ctx.route('**/api/**', route_api)
            p = ctx.new_page()
            p.goto(base + '/events.html')
            p.wait_for_selector('.ev-hero', timeout=10000)
            p.wait_for_timeout(500)
            m = measure(p, tag)

            if theme == 'light':
                ck(f'{tag}：浅色 hero 不加边框（深卡对浅底已可分离）',
                   m['st']['bw'] == 0, m['st'])
                ck(f'{tag}：浅色卡身与页面底亮度对比 ≥ 10（原有效果不回退）',
                   m['inner_ratio'] >= 10, f"{m['inner_ratio']:.2f}")
            else:
                ck(f'{tag}：夜间 hero 有一圈 1px 边框', m['st']['bw'] == 1, m['st'])
                ck(f'{tag}：边框像素 vs 页面底亮度对比 ≥ 2.0（不再融进背景）',
                   m['edge_ratio'] >= 2.0,
                   f"{m['edge_ratio']:.2f} @x={m['edge_x'] - 6}px、{m['row'][m['edge_x']]}")
                ck(f'{tag}：卡身与页面底仍有层次（≥ 1.10）', m['inner_ratio'] >= 1.10,
                   f"{m['inner_ratio']:.2f}")
            ctx.close()

        # ---------- 2. 在主题设置页应用配色后：内联令牌同样不能与页底同色 ----------
        # 🔴 回归来源：生成令牌曾把夜间 `--hero-1` 算成 neutral tone 6，而 M3 夜间 surface 就是
        # tone 6 ⇒ 卡面与页底**完全同色**（实测 1.01）。下面锁住"应用主题后仍分得开"。
        for preset in ('jade', 'clay'):
            tag = f'dark-{preset}-applied'
            ctx = browser.new_context(viewport={'width': 1280, 'height': 900}, service_workers='block')
            ctx.add_init_script("localStorage.setItem('band_token','probe');"
                                "sessionStorage.setItem('band.welcome.done','1');")
            ctx.route('**/api/**', route_api)
            p = ctx.new_page()
            p.goto(base + '/theme.html')
            p.wait_for_selector('#theme-preview', timeout=10000)
            p.locator(f'.theme-seed[data-p="{preset}"]').click()
            p.click('#theme-apply')
            p.wait_for_timeout(250)
            p.click('#night-btn')
            p.wait_for_function("() => document.documentElement.dataset.theme === 'dark'", timeout=4000)
            p.goto(base + '/events.html')
            p.wait_for_selector('.ev-hero', timeout=10000)
            p.wait_for_timeout(500)
            m = measure(p, tag)
            ck(f'{tag}：应用配色后夜间 hero 卡面不再与页底同色（≥ 1.05）',
               m['inner_ratio'] >= 1.05,
               f"{m['inner_ratio']:.2f}；--hero-1={m['st']['hero1']}、--bg={m['st']['pageBg']}")
            ck(f'{tag}：应用配色后仍有 1px 边线且边框对比 ≥ 2.0',
               m['st']['bw'] == 1 and m['edge_ratio'] >= 2.0,
               f"{m['edge_ratio']:.2f}")
            ctx.close()

        # ---------- 3. 旧缓存迁移：已应用过旧主题的用户，不点「应用」也不能继续同色 ----------
        # 旧版主题引擎把夜间 --hero-1 存成固定 tone 6（= 夜间 surface）。band-nav 读缓存时
        # 必须**跳过**这两个键，让样式表的 accent 掺色公式重算；否则用户得手动重选主题才好。
        tag = 'dark-stale-cache'
        stale = {'version': 2, 'tokens': {
            'light': {},
            'dark': {'--bg': '#0F1512', '--surface': '#090F0D', '--line': '#3F4945',
                     '--accent-dk': '#86D6BE', '--hero-1': '#0F1512', '--hero-2': '#191F1D'}}}
        ctx = browser.new_context(viewport={'width': 1280, 'height': 900}, service_workers='block')
        ctx.add_init_script(
            "localStorage.setItem('band_token','probe');"
            "sessionStorage.setItem('band.welcome.done','1');"
            "localStorage.setItem('band.theme','dark');"
            "localStorage.setItem('band.theme.settings.v2', %s);" % json.dumps(json.dumps(stale)))
        ctx.route('**/api/**', route_api)
        p = ctx.new_page()
        p.goto(base + '/events.html')
        p.wait_for_selector('.ev-hero', timeout=10000)
        p.wait_for_timeout(400)
        m = measure(p, tag)
        ck(f'{tag}：缓存里的旧 --hero-1 不再直接生效（跳过、由样式表重算）',
           m['st']['hero1'] != '#0F1512', m['st']['hero1'])
        ck(f'{tag}：旧配置下夜间卡面仍与页底分层（≥ 1.10）',
           m['inner_ratio'] >= 1.10, f"{m['inner_ratio']:.2f}")
        ck(f'{tag}：旧配置下 1px 边线仍在且对比 ≥ 2.0',
           m['st']['bw'] == 1 and m['edge_ratio'] >= 2.0, f"{m['edge_ratio']:.2f}")
        ctx.close()
        browser.close()
    srv.shutdown()

    bad = [c for c in CHECKS if not c[1]]
    print(f"\n{len(CHECKS) - len(bad)}/{len(CHECKS)} 通过")
    sys.exit(1 if bad else 0)


if __name__ == '__main__':
    main()
