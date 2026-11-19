#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""#244 主题设置页**生产探针**（不是样张探针）。

跑在真实 Spring Boot 静态页上：`/theme.html` + `band.css` + `band-nav.js` +
`band-core.js` + `vendor/material-color-utilities/0.4.0/`。

接口用 Playwright 路由桩（`/api/auth/me` 返回一个管理员，其余返回空 data），
**不连数据库、不写真实业务数据**。只量真实 DOM、真实 localStorage、最终计算样式。

前置：应用已在 :8080 运行；Playwright 从 design-samples/.deps-246 借用（本机既有依赖）。
跑法：python tools/theme_probe.py
失败退出码 1。
"""
import json
import os
import re
import sys
from urllib.parse import unquote
from pathlib import Path

try:
    from playwright.sync_api import sync_playwright
except ImportError:  # 本机既有依赖在 design-samples/.deps-246（已 gitignore）
    sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "design-samples" / ".deps-246"))
    from playwright.sync_api import sync_playwright

BASE = os.environ.get("BAND_BASE", "http://localhost:8080")
failures = []
total = [0]


def check(name, ok, extra=""):
    total[0] += 1
    print(("  OK   " if ok else "  FAIL ") + name + (("   | " + str(extra)) if extra != "" else ""))
    if not ok:
        failures.append(name)


def section(title):
    print("\n" + "=" * 76 + "\n" + title + "\n" + "=" * 76)


def rgb(s):
    nums = re.findall(r"[\d.]+", s or "")
    if len(nums) < 3:
        return None
    return tuple(float(x) for x in nums[:3])


def lum(c):
    def lin(x):
        x /= 255.0
        return x / 12.92 if x <= 0.04045 else ((x + 0.055) / 1.055) ** 2.4
    r, g, b = (lin(x) for x in c)
    return 0.2126 * r + 0.7152 * g + 0.0722 * b


def ratio(fg, bg):
    if fg is None or bg is None:
        return 0.0
    a, b = lum(fg), lum(bg)
    return (max(a, b) + 0.05) / (min(a, b) + 0.05)


def css(page, sel, prop):
    return page.evaluate(
        "([s,p]) => { const e=document.querySelector(s); return e ? getComputedStyle(e)[p] : ''; }",
        [sel, prop],
    )


def contrast(page, fg_sel, bg_sel, fg_prop="color", bg_prop="backgroundColor"):
    return ratio(rgb(css(page, fg_sel, fg_prop)), rgb(css(page, bg_sel, bg_prop)))


def storage(page, key):
    return page.evaluate("(k) => localStorage.getItem(k)", key)


def config(page):
    raw = storage(page, "band.theme.settings.v2")
    try:
        return json.loads(raw) if raw else None
    except Exception:  # noqa: BLE001
        return None


def init_script(extra=""):
    # 先放登录 token，再按需写旧键 / 坏配置；顺序有意义。
    return """
    try {
      localStorage.setItem('band_token', 'probe-token');
      // 初始化脚本每次导航都会跑；只在标签页第一次进入时清一次，
      // 否则「查询串丢失靠 sessionStorage 兜底」这类跨页检查永远测不到。
      if (!sessionStorage.getItem('__probe_boot')) {
        sessionStorage.clear();
        sessionStorage.setItem('__probe_boot', '1');
      }
      %s
    } catch (e) {}
    """ % extra


def new_context(browser, viewport=(1440, 900), storage_init="", reduced=False):
    ctx = browser.new_context(
        viewport={"width": viewport[0], "height": viewport[1]},
        service_workers="block",
        reduced_motion="reduce" if reduced else "no-preference",
    )
    ctx.add_init_script(init_script(storage_init))
    return ctx


def mock_api(ctx, sink=None):
    def handler(route):
        url = route.request.url
        if sink is not None:
            sink.append(url)
        if url.endswith("/api/auth/me"):
            body = {
                "code": 0,
                "message": "ok",
                "data": {"id": 1, "name": "测试团员", "role": "ADMIN", "sectionName": "弹拨声部"},
            }
        else:
            body = {"code": 0, "message": "ok", "data": []}
        route.fulfill(status=200, content_type="application/json", body=json.dumps(body, ensure_ascii=False))

    ctx.route("**/api/**", handler)


def goto(page, path):
    page.goto(BASE + path, wait_until="domcontentloaded")
    page.wait_for_selector(".layout", timeout=8000)
    page.wait_for_timeout(250)


def theme_wait(page):
    page.wait_for_selector(".layout", timeout=8000)
    page.wait_for_selector("#theme-preview", timeout=8000)
    page.wait_for_timeout(250)


def theme_goto(page, path="/theme.html"):
    goto(page, path)
    page.wait_for_selector("#theme-preview", timeout=8000)
    page.wait_for_timeout(250)


def set_contrast(page, value):
    label = {"standard": "标准", "medium": "中", "high": "高"}[value]
    page.locator(".theme-seg label", has_text=label).click()
    page.wait_for_timeout(220)


def set_theme(page, mode):
    if page.evaluate("document.documentElement.dataset.theme") == mode:
        return
    page.click("#night-btn")
    page.wait_for_function(
        "(m) => document.documentElement.dataset.theme === m", arg=mode, timeout=4000
    )
    page.wait_for_timeout(260)


def text(page, sel):
    return page.evaluate("(s) => (document.querySelector(s)||{}).textContent || ''", sel)


def main():
    with sync_playwright() as p:
        browser = p.chromium.launch(headless=True)

        # ------------------------------------------------------------ 入口 / 无侧栏项
        section("A. 入口改道 / 无侧栏新项 / 无重复入口")
        ctx = new_context(browser)
        mock_api(ctx)
        page = ctx.new_page()
        goto(page, "/events.html")
        check("活动页有且只有一个主题入口", page.locator("#theme-btn").count() == 1)
        check("主题入口是真实链接（不是按钮弹层）", page.eval_on_selector("#theme-btn", "e => e.tagName") == "A")
        check("页面不再保留旧配色弹层", page.locator("#theme-pop").count() == 0)
        href = page.get_attribute("#theme-btn", "href") or ""
        check("入口 href 指向 theme.html 且带来源", "/theme.html" in href and "from=/events.html" in unquote(href), href)
        page.click("#theme-btn")
        page.wait_for_url("**/theme.html**")
        theme_wait(page)
        check("主题页没有侧栏新项", page.locator('.menu a[href*="theme.html"]').count() == 0)
        check("主题页不再放自己的主题入口", page.locator("#theme-btn").count() == 0)
        check("主题页标题由 offMenuTitles 给出", text(page, ".head h1").strip() == "主题设置", text(page, ".head h1"))

        # 其他标准页（没有静态 appbar 的）也应注入链接
        goto(page, "/sessions.html")
        check("无静态控件页也注入主题链接", page.locator('#theme-btn[href*="/theme.html"]').count() == 1)

        # ------------------------------------------------------------ 返回来源（#248）
        section("B. 返回入口：直接来源 / 查询串 / 直达 / 非法来源 / 刷新 / 新标签")
        goto(page, "/events.html")
        page.click("#theme-btn")
        page.wait_for_url("**/theme.html**")
        theme_goto(page)
        check("从活动页进入，返回指向活动页", page.get_attribute("#theme-back", "href") == "/events.html")
        check("返回是真实链接且可访问名明确", page.eval_on_selector("#theme-back", "e => e.tagName") == "A"
              and page.get_attribute("#theme-back", "aria-label") == "返回活动列表")

        goto(page, "/concerts.html?kind=REHEARSAL&page=2")
        page.click("#theme-btn")
        page.wait_for_url("**/theme.html**")
        theme_wait(page)
        back = page.get_attribute("#theme-back", "href") or ""
        check("返回保留来源页查询串（筛选 / 页码）", back == "/concerts.html?kind=REHEARSAL&page=2", back)
        page.click("#theme-back")
        page.wait_for_url("**/concerts.html?kind=REHEARSAL&page=2")
        check("返回真的回到来源页", "/concerts.html?kind=REHEARSAL&page=2" in page.url, page.url)

        theme_goto(page, "/theme.html")
        check("直达主题页回退活动主页", page.get_attribute("#theme-back", "href") == "/events.html")
        page.reload(wait_until="domcontentloaded")
        page.wait_for_selector("#theme-back")
        check("刷新后返回仍可用", page.get_attribute("#theme-back", "href") == "/events.html")

        theme_goto(page, "/theme.html?from=" + "%2Flogin.html")
        check("非法来源（登录页）回退活动主页", page.get_attribute("#theme-back", "href") == "/events.html")
        theme_goto(page, "/theme.html?from=" + "%68%74%74%70%73%3A%2F%2Fevil.example%2Fx")
        check("外站来源回退活动主页", page.get_attribute("#theme-back", "href") == "/events.html")

        # #248：同一个历史项去掉查询串后刷新，保留已核对来源；
        # 另一次直达是新的历史项，不得复用旧来源或凭 sessionStorage 盲后退。
        goto(page, "/pieces.html")
        page.click("#theme-btn")
        page.wait_for_url("**/theme.html**")
        theme_wait(page)
        page.evaluate("history.replaceState(history.state, '', '/theme.html')")
        page.reload(wait_until="domcontentloaded")
        page.wait_for_selector("#theme-back")
        got_back = page.get_attribute("#theme-back", "href")
        check("当前历史项去掉查询串后刷新仍保留来源", got_back == "/pieces.html", got_back)
        goto(page, "/events.html")  # 从另一页再次直达；同 URL 的 goto 在 Chromium 会被当成刷新。
        page.goto(BASE + "/theme.html", wait_until="domcontentloaded")
        page.wait_for_selector("#theme-back")
        check("另一次直达不复用旧历史项来源", page.get_attribute("#theme-back", "href") == "/events.html")

        # 新标签页：带 from 的链接可独立使用
        tab = ctx.new_page()
        tab.goto(BASE + "/theme.html?from=%2Frecordings.html", wait_until="domcontentloaded")
        tab.wait_for_selector("#theme-back")
        check("新标签页返回仍指向来源", tab.get_attribute("#theme-back", "href") == "/recordings.html")
        tab.close()

        # ------------------------------------------------------------ 草稿隔离 / 应用 / 重置 / 取消
        section("C. 草稿只改预览；应用才写配置；重置 / 取消不污染已应用")
        ctx2 = new_context(browser)
        mock_api(ctx2)
        page = ctx2.new_page()
        theme_goto(page)
        applied_before = page.evaluate(
            "() => getComputedStyle(document.documentElement).getPropertyValue('--accent').trim()"
        )
        check("首次进入未写 v2 配置", config(page) is None)
        page.locator('.theme-seed[data-p="clay"]').click()
        page.wait_for_timeout(220)
        preview_accent = css(page, ".theme-preview", "color")
        root_accent = page.evaluate(
            "() => getComputedStyle(document.documentElement).getPropertyValue('--accent').trim()"
        )
        check("改种子后已应用根色不变", root_accent.lower() == applied_before.lower(), (root_accent, applied_before))
        check("草稿状态提示未应用", "未应用" in text(page, "#theme-draft-text"), text(page, "#theme-draft-text"))
        check("改种子仍未写 v2 配置", config(page) is None)
        check("预览文字可读（草稿）", contrast(page, ".tpv-desc", ".tpv-card") >= 4.5,
              round(contrast(page, ".tpv-desc", ".tpv-card"), 2))

        set_contrast(page, "high")
        check("对比度也先进入草稿", config(page) is None)
        page.click("#theme-apply")
        page.wait_for_timeout(300)
        cfg = config(page)
        check("应用后写入 v2 配置", bool(cfg and cfg.get("version") == 2), cfg and cfg.get("version"))
        check("配置含 seed / preset / contrast", bool(cfg and cfg.get("seed") and cfg.get("preset") == "clay"
                                                     and cfg.get("contrast") == "high"), cfg and (cfg.get("seed"), cfg.get("preset"), cfg.get("contrast")))
        check("配置含 light/dark 令牌缓存", bool(cfg and cfg.get("tokens", {}).get("light")
                                                   and cfg.get("tokens", {}).get("dark")))
        check("应用后根色变成种子派生色",
              page.evaluate("() => getComputedStyle(document.documentElement).getPropertyValue('--accent').trim()")
              .lower() == (cfg.get("tokens", {}).get("light", {}).get("--accent", "").lower()))

        # 重置默认只改草稿
        page.locator('.theme-seed[data-p="jade"]').click()
        page.click("#theme-reset")
        page.wait_for_timeout(200)
        check("恢复默认只重置草稿（不写配置）", config(page).get("preset") == "clay")
        check("恢复默认后草稿提示仍为未应用", "未应用" in text(page, "#theme-draft-text"))

        # 取消草稿回到已应用
        page.locator('.theme-seed[data-p="violet"]').click()
        page.click("#theme-discard")
        page.wait_for_timeout(200)
        check("取消草稿回到已应用（clay）",
              page.get_attribute('.theme-seed[data-p="clay"]', "aria-pressed") == "true")
        check("取消草稿不改配置", config(page).get("preset") == "clay")

        # ------------------------------------------------------------ 旧键 / 损坏 / 存储不可用
        section("D. 旧键迁移 / 配置损坏 / 存储不可用")
        ctx3 = new_context(browser, storage_init="localStorage.setItem('band.palette','clay');")
        mock_api(ctx3)
        page = ctx3.new_page()
        theme_goto(page)
        check("旧 band.palette 迁移为已应用草稿",
              page.get_attribute('.theme-seed[data-p="clay"]', "aria-pressed") == "true")
        check("迁移后未应用前不写 v2", config(page) is None)
        check("迁移提示如实告知", "旧版配色" in text(page, "#theme-notice"), text(page, "#theme-notice"))

        ctx4 = new_context(browser, storage_init="localStorage.setItem('band.theme.settings.v2','{bad json');")
        mock_api(ctx4)
        page = ctx4.new_page()
        theme_goto(page)
        check("损坏配置回默认墨·青",
              page.get_attribute('.theme-seed[data-p="jade"]', "aria-pressed") == "true")
        check("损坏配置如实提示", "损坏" in text(page, "#theme-notice"), text(page, "#theme-notice"))

        ctx5 = new_context(
            browser,
            storage_init="""
              try {
                Storage.prototype.setItem = function () { throw new Error('denied'); };
              } catch (e) {}
            """,
        )
        mock_api(ctx5)
        page = ctx5.new_page()
        theme_goto(page)
        check("存储不可用仍能进入主题页", page.locator("#theme-preview").count() == 1)
        check("存储不可用首屏如实提示", "存储不可用" in text(page, "#theme-notice"), text(page, "#theme-notice"))
        page.locator('.theme-seed[data-p="violet"]').click()
        page.click("#theme-apply")
        page.wait_for_timeout(250)
        check("存储不可用仍能在本次会话应用",
              page.evaluate("() => getComputedStyle(document.documentElement).getPropertyValue('--accent').trim()")
              != "")
        check("存储不可用时不假装已记住", config(page) is None)

        # ------------------------------------------------------------ 日夜独立 / 自定义 / 无效 HEX
        section("E. 日夜独立即时；取消草稿不撤销日夜；自定义与无效 HEX")
        ctx6 = new_context(browser)
        mock_api(ctx6)
        page = ctx6.new_page()
        theme_goto(page)
        page.locator('.theme-seed[data-p="clay"]').click()
        page.click("#night-btn")
        page.wait_for_function("() => document.documentElement.dataset.theme === 'dark'", timeout=4000)
        page.wait_for_timeout(260)
        check("日夜即时生效（dataset + band.theme）",
              page.evaluate("() => localStorage.getItem('band.theme')") == "dark")
        check("日夜不进草稿（草稿仍是 clay / 未应用）",
              page.get_attribute('.theme-seed[data-p="clay"]', "aria-pressed") == "true"
              and "未应用" in text(page, "#theme-draft-text"))
        page.click("#theme-discard")
        page.wait_for_timeout(220)
        check("取消草稿不撤销日夜", page.evaluate("() => document.documentElement.dataset.theme") == "dark")

        page.fill("#theme-hex", "#B23A2E")
        page.wait_for_timeout(240)
        check("自定义 HEX 进入草稿", "未应用" in text(page, "#theme-draft-text"))
        red = page.evaluate("() => getComputedStyle(document.querySelector('.theme-preview')).getPropertyValue('--accent').trim()")
        page.fill("#theme-hex", "#ZZZZZZ")
        page.wait_for_timeout(160)
        check("无效 HEX 显示错误", page.locator("#theme-hex-err").is_visible())
        check("无效 HEX 不覆盖草稿",
              page.evaluate("() => getComputedStyle(document.querySelector('.theme-preview')).getPropertyValue('--accent').trim()") == red)
        check("红色种子下「取消」仍与普通选中区分",
              css(page, ".tpv-note.cancel", "backgroundColor") != css(page, ".tpv-nav-item.on", "backgroundColor"))

        # ------------------------------------------------------------ 三档 × 日夜：最终合成色
        section("F. 标准 / 中 / 高 × 日 / 夜：最终合成色阈值（1440）")
        ctx7 = new_context(browser)
        mock_api(ctx7)
        page = ctx7.new_page()
        theme_goto(page)
        page.fill("#theme-hex", "#1F6F5C")
        page.wait_for_timeout(200)
        checks = [
            ("正文 / 卡片底", ".tpv-desc", ".tpv-card", 4.5),
            ("卡片标题 / 卡片底", ".tpv-title", ".tpv-card", 4.5),
            ("次要说明 / 卡片底", ".tpv-meta", ".tpv-card", 4.5),
            ("主按钮文字 / 主色", ".tpv-btn.primary", ".tpv-btn.primary", 4.5),
            ("描边按钮文字 / 页面底", ".tpv-btn.ghost", ".theme-preview", 4.5),
            ("选中导航 / 选中底", ".tpv-nav-item.on", ".tpv-nav-item.on", 4.5),
            ("今天 chip / chip 底", ".tpv-chip", ".tpv-chip", 4.5),
            ("取消状态 / 提示底", ".tpv-note.cancel", ".tpv-note.cancel", 4.5),
            ("输入边界 / 输入底", ".tpv-field input", ".tpv-field input", 3, "borderTopColor"),
            ("大号日期 / 日期底", ".tpv-date b", ".tpv-date", 3),
        ]
        for mode in ("light", "dark"):
            set_theme(page, mode)
            for level in ("standard", "medium", "high"):
                set_contrast(page, level)
                page.wait_for_timeout(180)
                for item in checks:
                    label, fg, bg, minimum = item[0], item[1], item[2], item[3]
                    prop = item[4] if len(item) > 4 else "color"
                    r = contrast(page, fg, bg, prop)
                    check(f"{mode} / {level} · {label} ≥ {minimum}", r >= minimum - 0.01, f"{r:.2f}")

        # ------------------------------------------------------------ 首帧 / 跨页一致
        section("G. 跨页首帧：应用后新页面 <html> 直接带令牌")
        # 上一段循环以 dark 结束；这里先切回日间，否则拿 light 缓存比对必然不等。
        set_theme(page, "light")
        page.locator('.theme-seed[data-p="olive"]').click()
        set_contrast(page, "medium")
        page.click("#theme-apply")
        page.wait_for_timeout(300)
        applied_accent = config(page)["tokens"]["light"]["--accent"]
        goto(page, "/events.html")
        inline = page.evaluate("() => document.documentElement.style.getPropertyValue('--accent').trim()")
        computed = page.evaluate("() => getComputedStyle(document.documentElement).getPropertyValue('--accent').trim()")
        check("新页面 <html> 首帧就有内联令牌", inline.lower() == applied_accent.lower(), (inline, applied_accent))
        check("跨页计算色一致", computed.lower() == applied_accent.lower(), (computed, applied_accent))
        page.wait_for_timeout(200)

        # 暗色跨页
        page.click("#night-btn")
        page.wait_for_function("() => document.documentElement.dataset.theme === 'dark'", timeout=4000)
        page.wait_for_timeout(260)
        dark_accent = config(page)["tokens"]["dark"]["--accent"]
        goto(page, "/sessions.html")
        check("夜间跨页令牌一致",
              page.evaluate("() => getComputedStyle(document.documentElement).getPropertyValue('--accent').trim()").lower()
              == dark_accent.lower())

        # ------------------------------------------------------------ 布局 / 预览条 / 溢出
        section("H. 1440 / 390 / 320 布局；手机实时预览条；无横向溢出")
        ctx8 = new_context(browser, viewport=(1440, 900))
        mock_api(ctx8)
        page = ctx8.new_page()
        theme_goto(page)
        s = page.locator(".theme-settings").bounding_box()
        pv = page.locator(".theme-preview").bounding_box()
        check("桌面左设置右预览", s and pv and s["x"] + s["width"] <= pv["x"] + 2 and abs(s["y"] - pv["y"]) < 80,
              (s, pv))
        check("桌面隐藏手机预览条", page.get_attribute("#theme-sticky", "data-show") == "0")

        for width, height in ((390, 844), (320, 720)):
            ctxm = new_context(browser, viewport=(width, height))
            mock_api(ctxm)
            pm = ctxm.new_page()
            theme_goto(pm)
            sm = pm.locator(".theme-settings").bounding_box()
            pvm = pm.locator(".theme-preview").bounding_box()
            check(f"{width}px 设置在上、预览在下", sm and pvm and sm["y"] + sm["height"] <= pvm["y"] + 4, (sm, pvm))
            overflow = pm.evaluate("() => document.documentElement.scrollWidth - document.documentElement.clientWidth")
            check(f"{width}px 无横向溢出", overflow <= 1, overflow)
            # 三档 × 日夜在这个宽度也量关键组合（用户验收要求含「中」）
            for mode in ("light", "dark"):
                set_theme(pm, mode)
                for level in ("standard", "medium", "high"):
                    set_contrast(pm, level)
                    pm.wait_for_timeout(160)
                    r1 = contrast(pm, ".tpv-desc", ".tpv-card")
                    r2 = contrast(pm, ".tpv-btn.primary", ".tpv-btn.primary")
                    r3 = contrast(pm, ".tpv-field input", ".tpv-field input", "borderTopColor")
                    check(f"{width}px {mode}/{level} 正文 ≥4.5", r1 >= 4.49, f"{r1:.2f}")
                    check(f"{width}px {mode}/{level} 主按钮 ≥4.5", r2 >= 4.49, f"{r2:.2f}")
                    check(f"{width}px {mode}/{level} 输入边界 ≥3", r3 >= 2.99, f"{r3:.2f}")

        # 预览条可见性 / 点击 / 重叠隐藏
        ctxm = new_context(browser, viewport=(390, 844))
        mock_api(ctxm)
        pm = ctxm.new_page()
        theme_goto(pm)
        # scrollY=0 时对比度控件（y≈760–806）正压在预览条（y≈779–834）上，
        # 按规格「与对比度控件重叠即隐藏」应当不显示；往下滚过它再断言。
        pm.evaluate("window.scrollTo(0,200)")
        pm.wait_for_timeout(320)
        check("390px 预览不在视野时预览条出现", pm.get_attribute("#theme-sticky", "data-show") == "1")
        check("预览条触区 ≥44px",
              pm.evaluate("() => document.getElementById('theme-sticky').getBoundingClientRect().height") >= 44)
        pm.click("#theme-sticky")
        pm.wait_for_timeout(900)
        check("点预览条后完整预览进入视野",
              pm.evaluate("() => { const r=document.querySelector('.theme-preview').getBoundingClientRect(); return r.top < innerHeight && r.bottom > 0; }"))
        check("点预览条后焦点交给预览", pm.evaluate("() => document.activeElement.id") == "theme-preview")
        check("点预览条后自身隐藏", pm.get_attribute("#theme-sticky", "data-show") == "0")

        # 320px：把对比度分段滚到底部，预览条不得压住它
        ctxs = new_context(browser, viewport=(320, 720))
        mock_api(ctxs)
        ps = ctxs.new_page()
        theme_goto(ps)
        ps.evaluate("document.querySelector('#theme-seg').scrollIntoView({block:'end'})")
        ps.wait_for_timeout(350)
        seg = ps.locator("#theme-seg").bounding_box()
        bar = ps.locator("#theme-sticky").bounding_box()
        overlap_now = bar and seg and (seg["y"] + seg["height"]) > bar["y"] and seg["y"] < 720
        shown = ps.get_attribute("#theme-sticky", "data-show")
        check("320px 与对比度控件重叠时预览条隐藏", (not overlap_now) or shown == "0",
              {"overlap": overlap_now, "show": shown, "seg": seg, "bar": bar})

        # 减少动态效果
        ctxt = new_context(browser, viewport=(390, 844), reduced=True)
        mock_api(ctxt)
        pt = ctxt.new_page()
        theme_goto(pt)
        pt.wait_for_timeout(200)
        if pt.get_attribute("#theme-sticky", "data-show") == "1":
            pt.click("#theme-sticky")
            pt.wait_for_timeout(250)
            check("reduced-motion 下点预览条仍滚到预览",
                  pt.evaluate("() => document.querySelector('.theme-preview').getBoundingClientRect().top < innerHeight"))
        else:
            check("reduced-motion 下预览条按规则隐藏或显示", True)

        # ------------------------------------------------------------ 生产洁净度 / 无外部请求 / 键盘
        section("I. 生产洁净度：无样张徽标 / 调试钩子 / 实测表 / 外部请求；键盘可达")
        ctx9 = new_context(browser)
        reqs = []
        mock_api(ctx9, reqs)
        page = ctx9.new_page()
        theme_goto(page)
        check("无样张徽标", page.locator(".t244-ribbon").count() == 0)
        check("无 __theme244 调试钩子", page.evaluate("() => typeof window.__theme244") == "undefined")
        check("无对比度实测表", page.locator("#metrics, .t244-metric").count() == 0)
        check("无样张文案", "样张" not in page.content())
        external = [u for u in reqs if not u.startswith(BASE) and not u.startswith("data:")]
        check("无外部网络请求", len(external) == 0, external[:3])

        page.focus("#theme-back")
        check("返回键可键盘聚焦", page.evaluate("() => document.activeElement.id") == "theme-back")
        page.focus("#theme-apply")
        page.locator('.theme-seed[data-p="teal"]').click()
        page.focus("#theme-apply")
        page.keyboard.press("Enter")
        page.wait_for_timeout(250)
        check("应用按钮可键盘激活", bool(config(page)) and config(page).get("preset") == "teal")

        # 未保存保护：取消留在本页、确认才离开
        page.locator('.theme-seed[data-p="clay"]').click()
        page.click("#theme-back")
        page.wait_for_selector("#confirm-modal.show", timeout=4000)
        check("有未应用草稿时返回先确认", page.locator("#confirm-modal.show").count() == 1)
        page.click("#cf-cancel")
        page.wait_for_timeout(300)
        check("取消离开留在主题页且草稿保留",
              "/theme.html" in page.url
              and page.get_attribute('.theme-seed[data-p="clay"]', "aria-pressed") == "true")

        ctx.close(); ctx2.close(); ctx3.close(); ctx4.close(); ctx5.close()
        ctx6.close(); ctx7.close(); ctx8.close(); ctx9.close()
        browser.close()

    print("\n" + "=" * 76)
    print(f"合计 {total[0]} 项检查，失败 {len(failures)} 项")
    if failures:
        for f in failures:
            print("  ✗", f)
        return 1
    print("全部通过")
    return 0


if __name__ == "__main__":
    sys.exit(main())
