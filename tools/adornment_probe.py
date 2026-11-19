"""#260 生产前端探针：装扮入口、头像名称 / 说明、空头像框栏目、草稿与原功能回归。

运行：python -X utf8 tools/adornment_probe.py
使用仓库 static 真文件和本机 Chrome，所有 API / 头像清单由隔离夹具接管；
不登录、不读写真实团员数据。证据输出至 gitignore 的 design-samples/shots-260/。
失败退出 1。
"""
import json
import threading
import traceback
from pathlib import Path

HERE = Path(__file__).resolve().parent.parent / 'design-samples'
import profile_navigation_fixture as f
from playwright.sync_api import sync_playwright

OUT = HERE / 'shots-260'
OUT.mkdir(exist_ok=True)
checks, errors = [], []

LONG_DESC = ('这是一段用于验收长文换行的说明，包含中文、English words 与标点。'
             '它应当自然换行，不出现横向滚动，也不遮挡下方的选择、预览或保存操作。' * 4)

MANIFEST = {
    'avatars': [
        {'id': 'fixture-static-a', 'name': '静态甲', 'file': 'fluent-frog.svg',
         'description': '第一段说明，<b>按纯文本显示</b>。\n第二行也要原样保留。'},
        {'id': 'fixture-static-b', 'name': '静态乙', 'file': 'fluent-cat.svg', 'description': ''},
        {'id': 'fixture-static-c', 'name': '静态丙', 'file': 'fluent-panda.svg', 'description': 123},
        {'id': 'fixture-static-d', 'name': '静态丁', 'file': 'fluent-rabbit.svg'},
        {'id': 'fixture-dynamic-e', 'name': '动态戊', 'file': 'band-sunlit-mountains.webp',
         'stillFile': 'band-sunlit-mountains-still.png', 'animated': True, 'description': LONG_DESC},
    ]
}
OLD_CACHE = [
    {'id': 'fixture-static-a', 'name': '静态甲', 'file': 'fluent-frog.svg'},
    {'id': 'fixture-static-b', 'name': '静态乙', 'file': 'fluent-cat.svg'},
    {'id': 'fixture-dynamic-e', 'name': '动态戊', 'file': 'band-sunlit-mountains.webp',
     'stillFile': 'band-sunlit-mountains-still.png', 'animated': True},
]

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


def ck(name, ok, detail=None):
    checks.append({'name': name, 'pass': bool(ok), 'detail': detail})
    print(('OK ' if ok else 'FAIL ') + name + (f' | {detail}' if detail is not None else ''), flush=True)


def has(page, sel):
    return page.locator(sel).count() > 0


def txt(page, sel):
    return page.locator(sel).inner_text() if has(page, sel) else ''


def vis(page, sel):
    return has(page, sel) and page.locator(sel).is_visible()


def get(page, sel, name):
    return page.locator(sel).get_attribute(name) if has(page, sel) else None


def click(page, sel):
    if not has(page, sel):
        return False
    page.locator(sel).click()
    page.wait_for_timeout(80)
    return True


def route_manifest(ctx):
    def handler(route):
        route.fulfill(status=200, content_type='application/json',
                      body=json.dumps(MANIFEST, ensure_ascii=False))
    ctx.route('**/vendor/avatars/manifest.json', handler)


def ready(page, base):
    page.goto(base + '/profile.html')
    page.wait_for_function('!!BAND.navigation && !!history.state?.bandNavigation')
    page.wait_for_timeout(400)


def open_edit(page):
    page.locator('#edit-btn').click()
    page.locator('#pn-tab-avatar').wait_for(state='visible')
    click(page, '#pn-tab-avatar')  # 默认外层是「签名」；本探针从「装扮」开始


def choose(page, avatar_id):
    if not click(page, f'[data-avatar-id="{avatar_id}"]'):
        return False
    return True


def desc_state(page):
    return page.evaluate("""()=>{const d=document.getElementById('av-desc');
        return {exists:!!d,hidden:!d||d.hidden,text:d?d.textContent:'',
                htmlChildren:d?d.children.length:0}}""")


def run():
    server = f.ThreadingHTTPServer(('127.0.0.1', 0), f.Handler)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    base = f'http://127.0.0.1:{server.server_port}'
    with sync_playwright() as pw:
        browser = pw.chromium.launch(
            executable_path=r'C:\Program Files\Google\Chrome\Application\chrome.exe',
            headless=True,
            args=['--disable-background-timer-throttling', '--disable-renderer-backgrounding',
                  '--disable-backgrounding-occluded-windows'])

        def context(avatar=None, role='MEMBER', cache=None, theme='light', width=1280, height=900):
            f.role = role
            f.profile['avatar'] = avatar
            f.requests.clear()
            ctx = browser.new_context(viewport={'width': width, 'height': height},
                                      service_workers='block',
                                      reduced_motion='no-preference')
            script = ("localStorage.setItem('band_token','isolated-fixture');"
                      "sessionStorage.setItem('band.welcome.done','1');"
                      f"localStorage.setItem('band.theme','{theme}');")
            if cache is not None:
                script += ("sessionStorage.setItem('band_avatars',"
                           + json.dumps(json.dumps(cache, ensure_ascii=False)) + ");")
            ctx.add_init_script(script)
            ctx.route('**/api/**', f.api)
            route_manifest(ctx)
            page = ctx.new_page()
            page.on('pageerror', lambda e: errors.append(str(e)))
            return ctx, page

        # ---------- A260-01 入口 / 标签 ----------
        ctx, p = context(avatar='fixture-static-a')
        ready(p, base)
        ck('编辑名片入口只有一个', p.locator('#edit-btn').count() == 1)
        ck('菜单仍只有一份个人资料入口',
           p.evaluate("BAND.menu.filter(m=>m.href==='profile.html').length") == 1)
        open_edit(p)
        ck('外层标签为 签名 / 装扮 / 资料',
           [txt(p, '#pn-tab-intro'), txt(p, '#pn-tab-avatar'), txt(p, '#pn-tab-details')]
           == ['签名', '装扮', '资料'])
        ck('装扮面板仍叫 pn-panel-avatar 且被外层 tab 控制',
           has(p, '#pn-panel-avatar') and get(p, '#pn-tab-avatar', 'aria-controls') == 'pn-panel-avatar')
        ck('内层标签存在：头像 / 头像框',
           p.locator('#pn-tabs-adorn [data-pn-subtab]').count() == 2
           and [txt(p, '#pn-subtab-avatar'), txt(p, '#pn-subtab-frame')] == ['头像', '头像框'])
        ck('内层默认选中头像',
           get(p, '#pn-subtab-avatar', 'aria-selected') == 'true'
           and vis(p, '#pn-subpanel-avatar') and not vis(p, '#pn-subpanel-frame'))
        ck('内外 tablist 是两层独立容器',
           has(p, '#pn-tabs-root[role=tablist]') and has(p, '#pn-tabs-adorn[role=tablist]')
           and p.locator('#pn-tabs-root #pn-tabs-adorn').count() == 0)
        # 内层键盘：只动内层
        if has(p, '#pn-subtab-avatar'):
            p.locator('#pn-subtab-avatar').focus()
            p.keyboard.press('ArrowRight')
            ck('内层右方向键切到头像框且焦点跟随',
               get(p, '#pn-subtab-frame', 'aria-selected') == 'true'
               and vis(p, '#pn-subpanel-frame')
               and p.evaluate("document.activeElement.id") == 'pn-subtab-frame')
            ck('内层切换不抢外层状态',
               get(p, '#pn-tab-avatar', 'aria-selected') == 'true' and vis(p, '#pn-panel-avatar'))
            p.keyboard.press('ArrowLeft')
            ck('内层左方向键回到头像',
               get(p, '#pn-subtab-avatar', 'aria-selected') == 'true'
               and vis(p, '#pn-subpanel-avatar'))
        else:
            ck('内层右方向键切到头像框且焦点跟随', False, '内层标签不存在')
            ck('内层切换不抢外层状态', False, '内层标签不存在')
            ck('内层左方向键回到头像', False, '内层标签不存在')
        # 外层键盘：只动外层
        p.locator('#pn-tab-intro').focus()
        p.keyboard.press('End')
        ck('外层 End 切到资料且内层状态不变',
           get(p, '#pn-tab-details', 'aria-selected') == 'true'
           and not vis(p, '#pn-panel-intro')
           and get(p, '#pn-subtab-avatar', 'aria-selected') == 'true')
        click(p, '#pn-tab-avatar')
        ck('外层切回装扮后内层仍记着头像',
           vis(p, '#pn-panel-avatar') and get(p, '#pn-subtab-avatar', 'aria-selected') == 'true')

        # ---------- A260-02 物料说明 ----------
        ck('没有重复的编辑面板', p.locator('#pf-edit').count() == 1)
        choose(p, 'fixture-static-a')
        ck('选中项显示名称', txt(p, '#av-name') == '静态甲')
        ds = desc_state(p)
        ck('有说明时显示一段纯文本',
           ds['exists'] and not ds['hidden'] and '按纯文本显示' in ds['text']
           and '\n第二行也要原样保留' in ds['text'] and ds['htmlChildren'] == 0, ds)
        ck('说明里不出现“暂无描述”占位',
           '暂无描述' not in txt(p, '#pn-panel-avatar'))
        ck('名称 / 说明各只有一处',
           p.locator('#av-name').count() == 1 and p.locator('#av-desc').count() == 1)
        ck('没有描述编辑框',
           p.locator('#pn-subpanel-avatar input, #pn-subpanel-avatar textarea').count() == 0)
        choose(p, 'fixture-static-b')
        ck('空字符串说明 = 无说明', desc_state(p)['hidden'])
        choose(p, 'fixture-static-c')
        ck('非字符串说明 = 无说明', desc_state(p)['hidden'])
        choose(p, 'fixture-static-d')
        ck('旧项无说明只显示名称', desc_state(p)['hidden'] and txt(p, '#av-name') == '静态丁')
        choose(p, 'fixture-static-a')
        cached = p.evaluate("JSON.parse(sessionStorage.getItem('band_avatars')||'[]')")
        ck('新清单写进会话缓存且保留 description',
           any(x.get('id') == 'fixture-static-a' and x.get('description') for x in cached),
           cached[:2])

        # ---------- A260-04 头像框栏目（#260 时是空态；秋日树框接入后应有真实选项） ----------
        picked_before = p.evaluate('picked')
        puts_before = len([r for r in f.requests if r['method'] == 'PUT'])
        click(p, '#pn-subtab-frame')
        ck('头像框栏目有“不用头像框”与已接入的秋日树框',
           has(p, '[data-frame-id="__none__"]') and has(p, '[data-frame-id="autumn-wrap"]')
           and p.locator('#pn-subpanel-frame button').count() >= 2)
        ck('切到头像框不改头像 / 草稿、不发保存请求',
           p.evaluate('picked') == picked_before
           and len([r for r in f.requests if r['method'] == 'PUT']) == puts_before)
        click(p, '#pn-subtab-avatar')
        ck('切回头像后所选说明仍在',
           not desc_state(p)['hidden'] and p.evaluate('picked') == 'fixture-static-a')

        # ---------- A260-03 草稿 / 保存 / 原功能 ----------
        choose(p, 'fixture-dynamic-e')
        ck('动态组选中且说明为长文',
           p.evaluate('picked') == 'fixture-dynamic-e' and not desc_state(p)['hidden'])
        choose(p, '__none__')
        ck('“不用头像”跨组唯一选中',
           p.evaluate('picked') is None
           and p.locator('#av-grid [aria-pressed="true"]').count() == 1)
        choose(p, 'fixture-static-d')
        ck('静态组再选后仍只一个选中',
           p.evaluate('picked') == 'fixture-static-d'
           and p.locator('#av-grid [aria-pressed="true"]').count() == 1)
        p.locator('#save').click()
        p.wait_for_function('!editing', timeout=6000)
        put = [r for r in f.requests if r['method'] == 'PUT' and r['path'] == 'me/profile'][-1]
        ck('保存只传 avatar id，不传说明 / 头像框',
           put['body'] == {'avatar': 'fixture-static-d'}, put['body'])
        open_edit(p)
        ck('保存成功重进回显所选与无说明',
           p.evaluate('picked') == 'fixture-static-d'
           and txt(p, '#av-name') == '静态丁' and desc_state(p)['hidden'])
        choose(p, 'fixture-static-a')
        f.fail.add('PUT me/profile')
        p.locator('#save').click()
        p.wait_for_function("document.getElementById('save').dataset.bdPhase==='error'", timeout=6000)
        ck('保存失败留在编辑且草稿保留',
           vis(p, '#pf-edit') and p.evaluate('picked') == 'fixture-static-a'
           and p.locator('#pn-fields').is_enabled())
        f.fail.clear()
        p.locator('#save').click()
        p.wait_for_function('!editing', timeout=6000)
        ck('失败后重试成功', p.evaluate('profile.avatar') == 'fixture-static-a')
        open_edit(p)
        choose(p, 'fixture-static-d')
        click(p, '#edit-cancel')
        p.locator('#confirm-modal').wait_for(state='visible')
        click(p, '#cf-cancel')
        p.locator('#confirm-modal').wait_for(state='hidden')
        ck('未保存取消可选择继续编辑且草稿仍在',
           vis(p, '#pf-edit') and p.evaluate('picked') == 'fixture-static-d')
        click(p, '#edit-cancel')
        click(p, '#cf-ok')
        p.wait_for_timeout(300)
        ck('确认放弃后完整还原已保存头像',
           not vis(p, '#pf-edit') and p.evaluate('picked') == 'fixture-static-a'
           and txt(p, '#av-name') == '静态甲')
        open_edit(p)
        choose(p, 'fixture-static-a')
        if has(p, '[data-avatar-id="fixture-static-a"]'):
            p.locator('[data-avatar-id="fixture-static-a"]').focus()
            p.emulate_media(reduced_motion='reduce')
            p.wait_for_timeout(300)
            ck('减少动态效果重绘后焦点不丢、选择与说明保持',
               p.evaluate("document.activeElement.dataset.avatarId") == 'fixture-static-a'
               and p.evaluate('picked') == 'fixture-static-a' and not desc_state(p)['hidden'])
            p.emulate_media(reduced_motion='no-preference')
            p.wait_for_timeout(200)
        else:
            ck('减少动态效果重绘后焦点不丢、选择与说明保持', False, '头像格缺少 data-avatar-id')

        # ---------- A260-05 显示与兼容 ----------
        def contrast(sel):
            return p.evaluate(CONTRAST, sel) if has(p, sel) and vis(p, sel) else None

        light_name, light_desc = contrast('#av-name'), contrast('#av-desc')
        ck('浅色主题所选名称 / 说明对比度 ≥ 4.5',
           bool(light_name) and bool(light_desc) and light_name['r'] >= 4.5 and light_desc['r'] >= 4.5,
           {'name': light_name, 'desc': light_desc})
        p.evaluate("document.documentElement.dataset.theme='dark'")
        p.wait_for_timeout(300)
        dark_name, dark_desc = contrast('#av-name'), contrast('#av-desc')
        ck('夜间主题所选名称 / 说明对比度 ≥ 4.5',
           bool(dark_name) and bool(dark_desc) and dark_name['r'] >= 4.5 and dark_desc['r'] >= 4.5,
           {'name': dark_name, 'desc': dark_desc})
        p.evaluate("document.documentElement.dataset.theme='light'")
        p.wait_for_timeout(200)
        for width, height in [(1280, 900), (390, 844), (320, 700)]:
            p.set_viewport_size({'width': width, 'height': height})
            p.wait_for_timeout(250)
            if has(p, '#av-desc'):
                box = p.locator('#av-desc').evaluate(
                    'e=>({sw:e.scrollWidth,cw:e.clientWidth,r:e.getBoundingClientRect().right})')
            else:
                box = None
            save_box = p.locator('#save').bounding_box() if has(p, '#save') else None
            ck(f'{width}px 长文无横向溢出且保存按钮在视口内',
               p.evaluate('document.documentElement.scrollWidth<=innerWidth')
               and bool(box) and box['sw'] <= box['cw'] + 1 and box['r'] <= width + 1
               and bool(save_box) and save_box['x'] + save_box['width'] <= width + 1,
               {'desc': box, 'save': save_box})
        p.screenshot(path=str(OUT / 'profile-adorn-desktop.png'), full_page=True)
        p.set_viewport_size({'width': 390, 'height': 844})
        p.screenshot(path=str(OUT / 'profile-adorn-390.png'), full_page=True)
        p.set_viewport_size({'width': 320, 'height': 700})
        p.screenshot(path=str(OUT / 'profile-adorn-320.png'), full_page=True)
        ctx.close()

        # ---------- 旧缓存 → 新清单 ----------
        ctx, p = context(avatar='fixture-static-a', cache=OLD_CACHE)
        ready(p, base)
        open_edit(p)
        choose(p, 'fixture-static-a')
        ck('旧缓存（无 description）兼容且新清单到达后补出说明',
           not desc_state(p)['hidden'] and p.evaluate('picked') == 'fixture-static-a',
           desc_state(p))
        cached = p.evaluate("JSON.parse(sessionStorage.getItem('band_avatars')||'[]')")
        ck('旧缓存被新清单更新且保留 description',
           any(x.get('id') == 'fixture-static-a' and x.get('description') for x in cached))
        ctx.close()

        # ---------- 未知 id 回退 ----------
        ctx, p = context(avatar='ghost-id')
        ready(p, base)
        ck('未知头像 id 回退姓名首字', txt(p, '#big-av') == '验')
        open_edit(p)
        ck('未知 id 时选中名称显示回退且无说明',
           txt(p, '#av-name') == '姓名首字回退' and desc_state(p)['hidden']
           and p.locator('#av-grid [aria-pressed="true"]').count() == 0)
        ctx.close()

        # ---------- 荣誉团员只读 ----------
        ctx, p = context(avatar='fixture-static-a', role='HONORARY')
        ready(p, base)
        ck('荣誉团员没有编辑入口与装扮标签',
           not vis(p, '#edit-entry') and p.locator('#edit-btn').count() == 1
           and p.locator('#pn-tab-avatar').count() == 1
           and vis(p, '#honorary-note'))
        ctx.close()
        browser.close()
    server.shutdown()

    (OUT / 'checks.json').write_text(
        json.dumps({'checks': checks, 'errors': errors}, ensure_ascii=False, indent=2), encoding='utf-8')
    failed = [c for c in checks if not c['pass']]
    print(f'\n{len(checks) - len(failed)}/{len(checks)} 通过；脚本错误 {len(errors)}')
    for e in errors:
        print('PAGEERROR ' + e)
    return 1 if failed or errors else 0


if __name__ == '__main__':
    try:
        raise SystemExit(run())
    except SystemExit:
        raise
    except Exception:
        traceback.print_exc()
        raise SystemExit(1)
