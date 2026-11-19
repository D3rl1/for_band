#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""「纯墨」预设回归探针（2026-11-17 用户反馈：主题色里原有的纯墨不是纯墨色）。

根因：M3 `SchemeTonalSpot`（2021 规范）把主色调色板色度强制拉到 32，
种子 `#1C1F21`（HCT 色度 4.3）被算成蓝色主色（浅色 #186584 / 夜间 #8DCFF2）。
本探针锁三件事：
  A. 主题页选中「纯墨」后，预览里的 `--accent` 是近中性墨色（RGB 通道极差 ≤ 12），
     浅色近黑、夜间近白 —— 不再是蓝色；
  B. 应用「纯墨」（rev 2）后，活动页拿到的 `--accent` 同样是中性墨色；
  C. **旧缓存迁移**：rev 缺失的旧「纯墨」配置（缓存里是蓝色令牌）不再直接生效，
     `band-nav.js` / `band-core.js` 退回 CSS 的 legacy ink 调色板，刷新即恢复墨色。

跑法：`python tools/theme_ink_probe.py`（静态页 + 接口桩，不连数据库）。失败退出码 1。
"""
import json
import re
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

SHOTS = ROOT / 'design-samples' / 'shots-dark'
CHECKS = []


def ck(name, ok, detail=None):
    CHECKS.append((name, bool(ok), detail))
    print(('OK  ' if ok else 'FAIL') + ' ' + name + ('' if detail is None else ' | ' + str(detail)))


def route_api(route):
    u = urlsplit(route.request.url)
    if u.path == '/api/events':
        f.respond(route, {'list': [], 'total': 0})
    else:
        f.api(route)


def rgb_hex(s):
    m = re.search(r'#([0-9a-fA-F]{6})', s or '')
    if not m:
        return None
    v = m.group(1)
    return tuple(int(v[i:i + 2], 16) for i in (0, 2, 4))


def spread(c):
    return max(c) - min(c) if c else 999


def lum(c):
    def ch(v):
        v /= 255.0
        return v / 12.92 if v <= 0.04045 else ((v + 0.055) / 1.055) ** 2.4
    return 0.2126 * ch(c[0]) + 0.7152 * ch(c[1]) + 0.0722 * ch(c[2])


def token(page, name):
    return page.evaluate("(n) => getComputedStyle(document.documentElement).getPropertyValue(n).trim()", name)


def preview_token(page, name):
    return page.evaluate("(n) => getComputedStyle(document.querySelector('#theme-preview')).getPropertyValue(n).trim()", name)


def main():
    srv = ThreadingHTTPServer(('127.0.0.1', 0), f.Handler)
    threading.Thread(target=srv.serve_forever, daemon=True).start()
    base = f'http://127.0.0.1:{srv.server_address[1]}'
    SHOTS.mkdir(parents=True, exist_ok=True)
    with sync_playwright() as pw:
        browser = pw.chromium.launch(
            executable_path=r'C:\Program Files\Google\Chrome\Application\chrome.exe', headless=True)

        # ---------- A. 主题页预览：纯墨草稿 ----------
        ctx = browser.new_context(viewport={'width': 1440, 'height': 900}, service_workers='block')
        ctx.add_init_script("localStorage.setItem('band_token','probe');"
                            "sessionStorage.setItem('band.welcome.done','1');")
        ctx.route('**/api/**', route_api)
        p = ctx.new_page()
        p.goto(base + '/theme.html')
        p.wait_for_selector('#theme-preview', timeout=10000)
        p.locator('.theme-seed[data-p="ink"]').click()
        p.wait_for_timeout(400)
        p.screenshot(path=str(SHOTS / 'theme-ink-probe-light.png'), full_page=True)
        light = preview_token(p, '--accent')
        lc = rgb_hex(light)
        ck('A1 纯墨·浅色预览主色是中性色（RGB 极差 ≤ 12）', spread(lc) <= 12, f'{light}、极差 {spread(lc)}')
        ck('A2 纯墨·浅色预览主色接近墨黑（亮度 ≤ 0.05）', lc and lum(lc) <= 0.05,
           f'{light}、亮度 {lum(lc):.3f}' if lc else light)

        p.click('#night-btn')
        p.wait_for_function("() => document.documentElement.dataset.theme === 'dark'", timeout=4000)
        p.wait_for_timeout(400)
        p.screenshot(path=str(SHOTS / 'theme-ink-probe-dark.png'), full_page=True)
        dark = preview_token(p, '--accent')
        dc = rgb_hex(dark)
        ck('A3 纯墨·夜间预览主色是中性色（RGB 极差 ≤ 12）', spread(dc) <= 12, f'{dark}、极差 {spread(dc)}')
        ck('A4 纯墨·夜间预览主色接近纸白（亮度 ≥ 0.55）', dc and lum(dc) >= 0.55,
           f'{dark}、亮度 {lum(dc):.3f}' if dc else dark)
        ctx.close()

        # ---------- B. 应用纯墨后，全站令牌 ----------
        for mode in ('light', 'dark'):
            ctx = browser.new_context(viewport={'width': 1280, 'height': 900}, service_workers='block')
            ctx.add_init_script("localStorage.setItem('band_token','probe');"
                                "sessionStorage.setItem('band.welcome.done','1');")
            ctx.route('**/api/**', route_api)
            p = ctx.new_page()
            p.goto(base + '/theme.html')
            p.wait_for_selector('#theme-preview', timeout=10000)
            p.locator('.theme-seed[data-p="ink"]').click()
            p.click('#theme-apply')
            p.evaluate("(m) => localStorage.setItem('band.theme', m)", mode)
            cfg = p.evaluate("() => JSON.parse(localStorage.getItem('band.theme.settings.v2'))")
            p.goto(base + '/events.html')
            p.wait_for_selector('.ev-hero', timeout=10000)
            p.wait_for_timeout(300)
            acc = token(p, '--accent')
            c = rgb_hex(acc)
            ck(f'B {mode}：应用纯墨后 --accent 仍是中性墨色（极差 ≤ 12）',
               spread(c) <= 12, f'{acc}、极差 {spread(c)}')
            ck(f'B {mode}：应用配置带 rev 2（新引擎口径）', cfg and cfg.get('rev') == 2, cfg and cfg.get('rev'))
            ctx.close()

        # ---------- C. 旧缓存迁移：rev 缺失 + 蓝色令牌 ----------
        stale = {'version': 2, 'seed': '#1C1F21', 'preset': 'ink', 'contrast': 'standard', 'tokens': {
            'light': {'--bg': '#F6FAFE', '--surface': '#FFFFFF', '--accent': '#186584',
                      '--accent-dk': '#8DCFF2', '--hero-1': '#F6FAFE', '--hero-2': '#EEF1F4'},
            'dark': {'--bg': '#0F1417', '--surface': '#0A0F12', '--accent': '#8DCFF2',
                     '--accent-dk': '#8DCFF2', '--hero-1': '#0F1417', '--hero-2': '#191F22'}}}
        for mode, expect in (('light', '#1c1f21'), ('dark', '#c9cccb')):
            ctx = browser.new_context(viewport={'width': 1280, 'height': 900}, service_workers='block')
            ctx.add_init_script(
                "localStorage.setItem('band_token','probe');"
                "sessionStorage.setItem('band.welcome.done','1');"
                f"localStorage.setItem('band.theme','{mode}');"
                "localStorage.setItem('band.theme.settings.v2', %s);" % json.dumps(json.dumps(stale)))
            ctx.route('**/api/**', route_api)
            p = ctx.new_page()
            p.goto(base + '/events.html')
            p.wait_for_selector('.ev-hero', timeout=10000)
            p.wait_for_timeout(300)
            acc = token(p, '--accent').lower()
            pal = p.evaluate("() => document.documentElement.dataset.palette")
            ck(f'C {mode}：旧纯墨缓存不再直接生效，退回 legacy ink 调色板',
               pal == 'ink', f'data-palette={pal}')
            ck(f'C {mode}：旧缓存下 --accent 是 legacy 墨色 {expect}', acc == expect, acc)
            ctx.close()

        browser.close()
    srv.shutdown()

    bad = [c for c in CHECKS if not c[1]]
    print(f"\n{len(CHECKS) - len(bad)}/{len(CHECKS)} 通过")
    sys.exit(1 if bad else 0)


if __name__ == '__main__':
    main()
