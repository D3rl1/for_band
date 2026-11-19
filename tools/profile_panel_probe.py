"""#259 个人栏探针：个人主页快捷入口、改密码提级按钮、声音 / 触觉弹簧开关。
运行：python -X utf8 tools/profile_panel_probe.py
使用仓库 static 真文件和本机 Chrome，所有 API 由隔离夹具接管；不登录、不读写真实团员数据。
依赖与 profile_navigation_probe 相同，从 design-samples/.deps-246 加载本机 Playwright。
证据输出至 gitignore 的 design-samples/shots-259/；失败退出 1。
"""
import sys, threading, re
from pathlib import Path
from urllib.parse import urlsplit
HERE = Path(__file__).resolve().parent.parent / 'design-samples'
import profile_navigation_fixture as f
from playwright.sync_api import sync_playwright

OUT = HERE / 'shots-259'
OUT.mkdir(exist_ok=True)
checks = []
errors = []

CAPTURE = """(ms=1500)=>{
  const e=document.getElementById('p-sound'),rows=(window.__cap={rows:[]}).rows;
  const rest=()=>e.checked?14:0,start=performance.now();
  window.__capDone=false;
  function sample(t){
    const p=getComputedStyle(e,'::before');
    const x=p.transform==='none'?0:new DOMMatrixReadOnly(p.transform).m41;
    rows.push({x,checked:e.checked,t:t-start});
    const settled=rows.length>=5 && rows.slice(-5).every(v=>Math.abs(v.x-rest())<.0001);
    if(t-start<ms || (!settled && t-start<ms+4000))requestAnimationFrame(sample);else window.__capDone=true;
  }
  requestAnimationFrame(sample);
}"""


CONTRAST = """(sel)=>{
  const el=document.querySelector(sel),s=getComputedStyle(el);
  const rgb=c=>(c.match(/[\\d.]+/g)||[]).slice(0,3).map(Number);
  const lum=c=>rgb(c).map(v=>{v/=255;return v<=.04045?v/12.92:((v+.055)/1.055)**2.4})
    .reduce((n,v,i)=>n+v*[.2126,.7152,.0722][i],0);
  const ratio=(a,b)=>{a=lum(a);b=lum(b);return (Math.max(a,b)+.05)/(Math.min(a,b)+.05)};
  let node=el,bg=null;
  while(node&&node!==document.documentElement){const c=getComputedStyle(node).backgroundColor;
    if(c&&c!=='transparent'&&!/rgba\\(0, 0, 0, 0\\)/.test(c)){bg=c;break;}node=node.parentElement;}
  if(!bg)bg=getComputedStyle(document.body).backgroundColor;
  return {fg:s.color,bg,r:Math.round(ratio(s.color,bg)*100)/100};
}"""


def contrast(page, sel):
    return page.evaluate(CONTRAST, sel)


def ck(name, ok, detail=None):
    checks.append({'name': name, 'pass': bool(ok), 'detail': detail})
    print(('OK ' if ok else 'FAIL ') + name + (f' | {detail}' if detail is not None else ''), flush=True)


def api(route):
    """夹具之外的端点：个人栏要的三块数据补上，别让面板取数报错。"""
    path = urlsplit(route.request.url).path[5:]
    if path.endswith('attendance-summary'):
        return f.respond(route, {k: {'expected': 0, 'present': 0, 'leave': 0, 'absent': 0, 'rate': None}
                                 for k in ['rehearsal', 'sectionClass']})
    if path == 'recordings':
        f.requests.append({'path': path, 'query': urlsplit(route.request.url).query, 'method': 'GET', 'body': None})
        return f.respond(route, {'list': [], 'total': 0})
    if path == 'me/recent-activity':
        return f.respond(route, [])
    if re.fullmatch(r'members/\d+/attendance-summary', path):
        return f.respond(route, {k: {'expected': 0, 'present': 0, 'leave': 0, 'absent': 0, 'rate': None}
                                 for k in ['rehearsal', 'sectionClass']})
    return f.api(route)


def make_context(browser, **kw):
    kw.setdefault('reduced_motion', 'no-preference')
    ctx = browser.new_context(service_workers='block', **kw)
    ctx.add_init_script("localStorage.setItem('band_token','isolated-fixture');"
                        "sessionStorage.setItem('band.welcome.done','1');")
    ctx.route('**/api/**', api)
    return ctx


def open_panel(page, base):
    page.goto(base + '/events.html')
    page.wait_for_function("typeof BAND!=='undefined' && !!BAND.me")
    page.wait_for_timeout(400)
    page.locator('.me').click()
    page.locator('#profile.open').wait_for(state='visible')
    page.wait_for_timeout(500)


def finish(page):
    page.wait_for_function('window.__capDone', timeout=20000)
    rows = page.evaluate('window.__cap.rows') or []
    xs = [r['x'] for r in rows]
    return rows, (max(xs) if xs else 0), (min(xs) if xs else 0), (xs[-1] if xs else None)


def run():
    server = f.ThreadingHTTPServer(('127.0.0.1', 0), f.Handler)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    base = f'http://127.0.0.1:{server.server_port}'
    chrome = r'C:\Program Files\Google\Chrome\Application\chrome.exe'
    with sync_playwright() as pw:
        b = pw.chromium.launch(executable_path=chrome, headless=True, args=[
            '--disable-background-timer-throttling', '--disable-renderer-backgrounding',
            '--disable-backgrounding-occluded-windows'])

        # ---------------- 桌面 1280：入口 / 按钮 / 开关 ----------------
        c = make_context(b, viewport={'width': 1280, 'height': 900})
        p = c.new_page()
        p.on('pageerror', lambda e: errors.append(str(e)))
        open_panel(p, base)
        ck('个人栏以 dialog 打开', p.locator('#profile').get_attribute('role') == 'dialog')

        home = p.locator('#profile .p-id .p-name-line .p-home')
        ck('「个人主页 ›」在身份行的姓名后面且指向可编辑个人页',
           home.count() == 1 and home.get_attribute('href').endswith('/profile.html')
           and '个人主页' in home.inner_text(), home.inner_text())
        nb, hb = p.locator('#profile .p-id .p-name').bounding_box(), home.bounding_box()
        ck('入口在姓名右后方、与姓名同一行',
           hb['x'] >= nb['x'] + nb['width'] - 1 and abs(hb['y'] - nb['y']) < 4,
           {'name': nb, 'home': hb})
        ck('「最近」那行只剩「看全部」（不再并排两个入口）',
           p.locator('#profile .p-flow-head a').count() == 1
           and p.locator('#profile .p-flow-head .p-home').count() == 0)

        pwbtn = p.locator('#profile #p-pw')
        bbox = pwbtn.bounding_box()
        panel_box = p.locator('#profile').bounding_box()
        style = p.evaluate("""()=>{const b=document.getElementById('p-pw'),s=getComputedStyle(b);
            const p=document.getElementById('profile'),ps=getComputedStyle(p);
            return {tag:b.tagName,border:s.borderTopStyle+' '+s.borderTopWidth,radius:s.borderRadius,
                    inner:p.clientWidth-parseFloat(ps.paddingLeft)-parseFloat(ps.paddingRight)}}""")
        ck('改密码是 <button> 且全宽描边',
           style['tag'] == 'BUTTON' and 'solid' in style['border']
           and abs(bbox['width'] - style['inner']) <= 1, {'box': bbox, 'style': style})
        ck('改密码触控高 ≥ 44', bbox['height'] >= 44, bbox['height'])
        for sel, label in [('#profile .p-home', '个人主页'), ('#profile #p-pw', '改密码'),
                           ('#profile .p-prefs .band-switch span', '声音'), ('#profile .p-acts a', '退出')]:
            got = contrast(p, sel)
            ck(f'{label} 文字对比度 ≥ 4.5', got['r'] >= 4.5, got)
        p.evaluate("document.documentElement.dataset.theme='dark'")
        p.wait_for_timeout(300)
        for sel, label in [('#profile .p-home', '个人主页'), ('#profile #p-pw', '改密码'),
                           ('#profile .p-prefs .band-switch span', '声音'), ('#profile .p-acts a', '退出')]:
            got = contrast(p, sel)
            ck(f'夜间 {label} 文字对比度 ≥ 4.5', got['r'] >= 4.5, got)
        p.evaluate("document.documentElement.dataset.theme='light'")
        p.wait_for_timeout(200)

        form = p.locator('#profile #p-pw-form')
        ck('改密码表单默认收起且 aria-expanded=false',
           form.is_hidden() and pwbtn.get_attribute('aria-expanded') == 'false'
           and pwbtn.get_attribute('aria-controls') == 'p-pw-form')
        pwbtn.click()
        ck('点一下展开表单并同步 aria-expanded',
           form.is_visible() and pwbtn.get_attribute('aria-expanded') == 'true')
        pwbtn.click()
        ck('再点收起且 aria-expanded 回 false',
           form.is_hidden() and pwbtn.get_attribute('aria-expanded') == 'false')

        ck('两枚开关具备 switch 语义与可访问名',
           p.get_by_role('switch', name='声音').count() == 1
           and p.get_by_role('switch', name='触觉').count() == 1)
        ck('初值直接到端点（未设置 = 开，不播动画）',
           p.locator('#p-sound').is_checked() and p.locator('#p-haptic').is_checked()
           and abs(p.locator('#p-sound').evaluate(
               "e=>new DOMMatrixReadOnly(getComputedStyle(e,'::before').transform).m41") - 14) < .05)

        p.evaluate("window.__chg=0;document.getElementById('p-sound').addEventListener('change',()=>window.__chg++)")
        before = len(f.requests)
        track_x = p.locator('#p-sound').bounding_box()['x']
        p.evaluate(CAPTURE)
        p.locator('label:has(#p-sound) span').click()
        rows, mx, mn, last = finish(p)
        ck('关闭方向弹簧越过并收敛', mn < -1 and abs(last) < .05, {'min': mn, 'last': last})
        ck('轨道位置不动', abs(p.locator('#p-sound').bounding_box()['x'] - track_x) < .5)
        ck('一次点击只发一次 change', p.evaluate('window.__chg') == 1, p.evaluate('window.__chg'))
        ck('关闭写入 band_sound=0', p.evaluate("localStorage.getItem('band_sound')") == '0')
        ck('切换偏好不发任何请求', len(f.requests) == before, len(f.requests) - before)

        p.evaluate(CAPTURE)
        p.locator('#p-sound').focus()
        p.keyboard.press('Space')
        rows, mx, mn, last = finish(p)
        ck('空格开启：一次 change、写 1、到端点', p.evaluate('window.__chg') == 2
           and p.evaluate("localStorage.getItem('band_sound')") == '1'
           and mx > 15 and abs(last - 14) < .05,
           {'max': mx, 'last': last, 'chg': p.evaluate('window.__chg')})

        p.locator('label:has(#p-haptic) span').click()
        p.wait_for_timeout(300)
        ck('触觉独立写 band_haptics=0（不与声音混用）',
           p.evaluate("localStorage.getItem('band_haptics')") == '0'
           and p.evaluate("localStorage.getItem('band_sound')") == '1')

        p.keyboard.press('Escape')
        p.wait_for_timeout(300)
        ck('Esc 关闭面板', not p.locator('#profile').evaluate("e=>e.classList.contains('open')"))

        before = len([r for r in f.requests if r['method'] == 'POST'])
        open_panel(p, base)
        p.locator('#p-logout').click()
        p.wait_for_timeout(600)
        ck('退出仍发 POST /api/auth/logout',
           len([r for r in f.requests if r['method'] == 'POST' and r['path'] == 'auth/logout']) > before)
        c.close()

        # ---------------- 无触觉能力：整行不渲染 ----------------
        c = make_context(b, viewport={'width': 1280, 'height': 900})
        c.add_init_script("Object.defineProperty(navigator,'vibrate',{value:undefined,configurable:true});"
                          "delete window.BandNative;")
        p = c.new_page()
        p.on('pageerror', lambda e: errors.append(str(e)))
        open_panel(p, base)
        ck('无触觉能力时整行不渲染、声音行仍在',
           p.locator('#p-haptic').count() == 0 and p.locator('#p-sound').count() == 1)
        c.close()

        # ---------------- 荣誉团员：不给个人主页快捷入口 ----------------
        f.role = 'HONORARY'
        c = make_context(b, viewport={'width': 1280, 'height': 900})
        p = c.new_page()
        p.on('pageerror', lambda e: errors.append(str(e)))
        open_panel(p, base)
        ck('荣誉团员不渲染个人主页（与菜单白名单一致）',
           p.locator('#profile .p-home').count() == 0
           and p.locator('#profile .p-flow-head a:not(.p-home)').count() == 1)
        c.close()
        f.role = 'ADMIN'

        # ---------------- 减少动态效果：直接到端点 ----------------
        c = make_context(b, viewport={'width': 1280, 'height': 900}, reduced_motion='reduce')
        p = c.new_page()
        p.on('pageerror', lambda e: errors.append(str(e)))
        open_panel(p, base)
        p.evaluate(CAPTURE, 300)
        p.locator('label:has(#p-sound) span').click()
        rows, mx, mn, last = finish(p)
        post = [r['x'] for r in rows if not r['checked']]   # 只看切换之后（之前停在端点 14 不算运动）
        ck('减少动态效果下无运动、直接到端点',
           bool(post) and all(abs(x) < .05 for x in post) and abs(last) < .05,
           {'post_max': max(post) if post else None, 'last': last})
        c.close()

        # ---------------- 手机 390 / 320：滚动与不溢出 ----------------
        for w, h in [(390, 844), (320, 568)]:
            c = make_context(b, viewport={'width': w, 'height': h})
            p = c.new_page()
            p.on('pageerror', lambda e: errors.append(str(e)))
            p.goto(base + '/events.html')
            p.wait_for_function("typeof BAND!=='undefined' && !!BAND.me")
            p.wait_for_timeout(400)
            p.locator('.mav-toggle').click()
            p.wait_for_timeout(400)
            p.locator('.rail .me').click()
            p.locator('#profile.open').wait_for(state='visible')
            p.wait_for_timeout(500)
            geo = p.evaluate("""()=>{const e=document.getElementById('profile');
                return {sw:e.scrollWidth,cw:e.clientWidth,sc:e.scrollHeight,ch:e.clientHeight,
                        ov:getComputedStyle(e).overflowY}}""")
            ck(f'{w}px 面板无横向溢出且可纵向滚动',
               geo['sw'] <= geo['cw'] + 1 and geo['ov'] in ('auto', 'scroll'), geo)
            nm = p.evaluate("""()=>{const l=document.querySelector('#profile .p-name-line');
                return l?{sw:l.scrollWidth,cw:l.clientWidth}:null}""")
            ck(f'{w}px 姓名行不溢出（姓名 + 个人主页仍在行内）',
               nm and nm['sw'] <= nm['cw'] + 1
               and p.locator('#profile .p-name-line .p-home').is_visible(), nm)
            p.evaluate("document.getElementById('profile').scrollTop=99999")
            p.wait_for_timeout(250)
            out = p.locator('#p-logout').bounding_box()
            back = p.locator('.p-back').bounding_box()
            ck(f'{w}px 滚到底能看到退出', bool(out) and out['y'] >= 0 and out['y'] + out['height'] <= h + 1, out)
            ck(f'{w}px 「← 回抽屉」滚动后仍在视口内',
               bool(back) and back['y'] >= 0 and back['y'] + back['height'] <= h + 1, back)
            p.screenshot(path=str(OUT / f'panel-{w}.png'))
            c.close()

        # ---------------- 桌面截图（桌面浅色） ----------------
        c = make_context(b, viewport={'width': 1280, 'height': 900})
        p = c.new_page()
        p.on('pageerror', lambda e: errors.append(str(e)))
        open_panel(p, base)
        p.locator('#profile').screenshot(path=str(OUT / 'panel-desktop.png'))
        ck('桌面无 JS 报错', not errors, errors[:3])
        c.close()
        b.close()

    ok = sum(1 for x in checks if x['pass'])
    print(f'\n{ok}/{len(checks)} 通过', flush=True)
    if errors:
        print('页面错误：', errors[:5], flush=True)
    return 0 if ok == len(checks) and not errors else 1


if __name__ == '__main__':
    try:
        sys.exit(run())
    except Exception:
        import traceback
        traceback.print_exc()
        sys.exit(1)
