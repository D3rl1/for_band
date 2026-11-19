"""#262 侧栏「专场」撤下探针：导航里不能再点到 showcase.html，入口只剩演出页那张卡。
运行：python -X utf8 tools/menu_showcase_probe.py
使用仓库 static 真文件 + 本机 Chrome，API 由隔离夹具接管；不登录、不读写真实数据。
证据输出至 gitignore 的 design-samples/shots-261/；失败退出 1。
"""
import sys, threading
from pathlib import Path
HERE = Path(__file__).resolve().parent.parent / 'design-samples'
import profile_navigation_fixture as f
from playwright.sync_api import sync_playwright

OUT = HERE / 'shots-261'
OUT.mkdir(exist_ok=True)
checks = []
errors = []


def ck(name, ok, detail=None):
    checks.append({'name': name, 'pass': bool(ok)})
    print(('OK ' if ok else 'FAIL ') + name + (f' | {detail}' if detail is not None else ''), flush=True)


def make_context(browser, **kw):
    ctx = browser.new_context(service_workers='block', **kw)
    ctx.add_init_script("localStorage.setItem('band_token','isolated-fixture');"
                        "sessionStorage.setItem('band.welcome.done','1');")
    ctx.route('**/api/**', f.api)
    return ctx


def nav_links(page, scope='.rail'):
    return page.evaluate("(s)=>[...document.querySelectorAll(s+' .menu a')].map(a=>a.getAttribute('href'))", scope)


def run():
    server = f.ThreadingHTTPServer(('127.0.0.1', 0), f.Handler)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    base = f'http://127.0.0.1:{server.server_port}'
    with sync_playwright() as pw:
        b = pw.chromium.launch(executable_path=r'C:\Program Files\Google\Chrome\Application\chrome.exe',
                               headless=True, args=['--disable-background-timer-throttling',
                                                    '--disable-renderer-backgrounding',
                                                    '--disable-backgrounding-occluded-windows'])

        # ---- 桌面：日常视图不再有「专场」----
        c = make_context(b, viewport={'width': 1280, 'height': 900})
        p = c.new_page()
        p.on('pageerror', lambda e: errors.append(str(e)))
        p.goto(base + '/events.html')
        p.wait_for_function("typeof BAND!=='undefined' && !!BAND.me")
        p.wait_for_timeout(500)
        hrefs = nav_links(p)
        ck('桌面侧栏没有指向 showcase.html 的项',
           not [h for h in hrefs if h and h.endswith('showcase.html')], hrefs)
        ck('菜单总表 24 → 23（日常 13 + 管理 10）',
           p.evaluate('BAND.menu.length') == 23 and len(hrefs) == 13,
           {'total': p.evaluate('BAND.menu.length'), 'daily': len(hrefs)})
        ck('「演出」仍在（活动与演出组没被误删）',
           any((h or '').endswith('concerts.html') for h in hrefs))
        p.locator('.rail .menu a[href$="concerts.html"]').first.click()
        p.wait_for_timeout(600)
        ck('演出页入口卡仍指向 /showcase.html',
           p.locator('.sc-entry').get_attribute('href').endswith('/showcase.html'))
        p.locator('.sc-entry').click()
        p.locator('#cf-ok').click()          # 入口卡先弹「是否要跳转到过去的时间线？」
        p.wait_for_url('**/showcase.html', timeout=15000)
        p.wait_for_timeout(800)
        ck('从入口卡能进专场页', p.url.endswith('/showcase.html'))
        hidden = p.evaluate("""()=>['.nav-slot','.head'].map(s=>{
            const e=document.querySelector(s);return e?getComputedStyle(e).display:'missing'})""")
        ck('沉浸页仍隐藏侧栏与页头', hidden == ['none', 'none'], hidden)
        p.screenshot(path=str(OUT / 'showcase-desktop.png'))
        c.close()

        # ---- 管理视图：「专场策展」不许跟着一起被删 ----
        c = make_context(b, viewport={'width': 1280, 'height': 900})
        p = c.new_page()
        p.on('pageerror', lambda e: errors.append(str(e)))
        p.goto(base + '/events.html')
        p.wait_for_function("typeof BAND!=='undefined' && !!BAND.me")
        p.wait_for_timeout(500)
        # 桌面侧栏收起时开关不可见 ⇒ 直接派发 click（事件委托在 `.vswitch` 上，照常生效）
        p.locator('.vswitch button[data-v="管理"]').evaluate("b=>b.click()")
        p.wait_for_timeout(500)
        hrefs = nav_links(p)
        ck('管理视图仍有「专场策展」(admin-showcase.html)',
           any((h or '').endswith('admin-showcase.html') for h in hrefs), hrefs)
        ck('管理视图也没有 showcase.html',
           not [h for h in hrefs if h and h.endswith('/showcase.html')], hrefs)
        c.close()

        # ---- 手机抽屉：同样没有「专场」----
        c = make_context(b, viewport={'width': 390, 'height': 844})
        p = c.new_page()
        p.on('pageerror', lambda e: errors.append(str(e)))
        p.goto(base + '/events.html')
        p.wait_for_function("typeof BAND!=='undefined' && !!BAND.me")
        p.wait_for_timeout(500)
        p.locator('.mav-toggle').click()
        p.wait_for_timeout(500)
        hrefs = nav_links(p)
        ck('手机抽屉没有指向 showcase.html 的项',
           not [h for h in hrefs if h and h.endswith('showcase.html')], hrefs)
        p.screenshot(path=str(OUT / 'drawer-390.png'))
        ck('全程无 JS 报错', not errors, errors[:3])
        c.close()
        b.close()

    ok = sum(1 for x in checks if x['pass'])
    print(f'\n{ok}/{len(checks)} 通过', flush=True)
    return 0 if ok == len(checks) and not errors else 1


if __name__ == '__main__':
    try:
        sys.exit(run())
    except Exception:
        import traceback
        traceback.print_exc()
        sys.exit(1)
