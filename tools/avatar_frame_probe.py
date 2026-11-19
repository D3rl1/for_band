"""秋日树生产探针：选择 / 保存 / 清空、文字层（头像与框的 description）、全站头像渲染、小头像静态降级、reduce 与页面报错。"""
import sys, json, threading, time
from pathlib import Path
from http.server import ThreadingHTTPServer
sys.stdout.reconfigure(encoding='utf-8')
sys.path.insert(0, str(Path(__file__).resolve().parent))
import profile_navigation_fixture as f

CHECKS = []
ERRORS = []


def ck(name, ok, detail=None):
    CHECKS.append((name, bool(ok), detail))
    print(('OK  ' if ok else 'FAIL') + ' ' + name + ('' if detail is None else ' | ' + str(detail)))


def has(p, sel):
    return p.locator(sel).count() > 0


def vis(p, sel):
    return has(p, sel) and p.locator(sel).first.is_visible()


def canvas_state(p, sel):
    return p.evaluate("""sel => {
      const c = document.querySelector(sel);
      if (!c) return null;
      const r = c.getBoundingClientRect();
      const d = c.getContext('2d').getImageData(0, 0, c.width, c.height).data;
      let painted = 0;
      for (let i = 3; i < d.length; i += 4) if (d[i]) painted++;
      return {w: c.width, h: c.height, cssW: r.width, cssH: r.height,
              painted, pointer: getComputedStyle(c).pointerEvents,
              url: c.toDataURL()};
    }""", sel)


def reset(frame=None, role='MEMBER'):
    f.profile.update({'avatar': 'fluent-frog', 'avatarFrame': frame, 'intro': '隔离资料',
                      'contact': 'demo', 'birthday': '2003-05-18'})
    f.member.update(f.profile)
    f.member.update({'sectionId': 1, 'activated': True})
    f.role = role
    f.requests.clear()
    f.errors.clear()


def new_context(browser, width=1280, height=900, frame=None, role='MEMBER', reduced=False):
    reset(frame, role)
    ctx = browser.new_context(viewport={'width': width, 'height': height},
                              service_workers='block',
                              reduced_motion='reduce' if reduced else 'no-preference')
    ctx.add_init_script("localStorage.setItem('band_token','isolated-fixture');"
                        "sessionStorage.setItem('band.welcome.done','1');")
    ctx.route('**/api/**', lambda route: f.api(route))
    p = ctx.new_page()
    p.on('pageerror', lambda e: ERRORS.append(str(e)))
    return ctx, p


def main():
    srv = ThreadingHTTPServer(('127.0.0.1', 0), f.Handler)
    threading.Thread(target=srv.serve_forever, daemon=True).start()
    base = f'http://127.0.0.1:{srv.server_address[1]}'
    with f.sync_playwright() as pw:
        browser = pw.chromium.launch(
            executable_path=r'C:\Program Files\Google\Chrome\Application\chrome.exe',
            headless=True,
            args=['--disable-background-timer-throttling', '--disable-renderer-backgrounding',
                  '--disable-backgrounding-occluded-windows'])

        # ---------- 1. 本人资料页：全尺寸框 + 选择 / 保存 / 清空 ----------
        ctx, p = new_context(browser, frame=None)
        p.goto(base + '/profile.html')
        p.wait_for_selector('#big-av .band-avatar-inner', timeout=10000)
        ck('无框时预览只有头像，不凭空挂框', not has(p, '#big-av canvas'))
        p.locator('#edit-btn').click()
        p.wait_for_selector('#pf-edit', state='visible')
        p.locator('#pn-tab-avatar').click()
        p.locator('#pn-subtab-frame').click()
        p.wait_for_selector('[data-frame-id="autumn-wrap"]', state='visible')
        ck('头像框栏目出现「不用头像框」+ 秋日树',
           has(p, '[data-frame-id="__none__"]') and has(p, '[data-frame-id="autumn-wrap"]'))
        ck('框名称已定稿为「秋日树」（不再是工作名「秋日树框」）',
           p.locator('[data-frame-id="autumn-wrap"]').get_attribute('title') == '秋日树')
        ck('未选框时不显示框文案（文字层随选中项出现）', not vis(p, '#fr-desc'))
        p.locator('[data-frame-id="autumn-wrap"]').click()
        ck('选中框后草稿 pickedFrame 为 autumn-wrap',
           p.evaluate('pickedFrame') == 'autumn-wrap')
        ck('选中框后名称下方显示文案「无边落木萧萧下」',
           vis(p, '#fr-desc') and p.locator('#fr-desc').inner_text().strip() == '无边落木萧萧下'
           and p.locator('#fr-name').inner_text().strip() == '秋日树')
        p.wait_for_selector('#big-av canvas', timeout=10000)
        p.wait_for_function("""() => {
          const c = document.querySelector('#big-av canvas'); return c && c.width > 0;
        }""", timeout=10000)
        st = canvas_state(p, '#big-av canvas')
        ck('预览 canvas 已绘制且比头像大（伸出圆外）',
           st and st['painted'] > 0 and st['cssW'] > 104 * 1.25, st)
        ck('框 canvas 不接收指针', st and st['pointer'] == 'none', st)
        p.locator('#save').click()
        p.wait_for_function("() => !editing", timeout=10000)
        put = [r for r in f.requests if r['method'] == 'PUT' and r['path'] == 'me/profile'][-1]
        ck('只改框时保存体只传 avatarFrame',
           put['body'] == {'avatarFrame': 'autumn-wrap'}, put['body'])
        p.wait_for_selector('#big-av canvas', timeout=10000)
        ck('保存后已保存资料的预览仍挂框', has(p, '#big-av canvas'))
        ctx.close()

        # 清空框
        ctx, p = new_context(browser, frame='autumn-wrap')
        p.goto(base + '/profile.html')
        p.wait_for_selector('#big-av canvas', timeout=10000)
        p.locator('#edit-btn').click()
        p.wait_for_selector('#pf-edit', state='visible')
        p.locator('#pn-tab-avatar').click()
        p.locator('#pn-subtab-frame').click()
        p.locator('[data-frame-id="__none__"]').click()
        p.locator('#save').click()
        p.wait_for_function("() => !editing", timeout=10000)
        put = [r for r in f.requests if r['method'] == 'PUT' and r['path'] == 'me/profile'][-1]
        ck('选「不用头像框」保存走 clearAvatarFrame',
           put['body'] == {'clearAvatarFrame': True}, put['body'])
        ck('清空后预览不再挂框', not has(p, '#big-av canvas'))
        ctx.close()

        # ---------- 2. 取消还原 ----------
        ctx, p = new_context(browser, frame=None)
        p.goto(base + '/profile.html')
        p.wait_for_selector('#big-av .band-avatar-inner', timeout=10000)
        p.locator('#edit-btn').click()
        p.wait_for_selector('#pf-edit', state='visible')
        p.locator('#pn-tab-avatar').click()
        p.locator('#pn-subtab-frame').click()
        p.locator('[data-frame-id="autumn-wrap"]').click()
        ck('取消前草稿已选框', p.evaluate('pickedFrame') == 'autumn-wrap')
        p.locator('#edit-cancel').click()
        p.locator('#confirm-modal').wait_for(state='visible')
        p.locator('#cf-ok').click()
        p.wait_for_function("() => !editing", timeout=10000)
        ck('取消并放弃后还原为无框', p.evaluate('pickedFrame') is None and not has(p, '#big-av canvas'))
        ctx.close()

        # ---------- 3. 未知框 id 回退无框 ----------
        ctx, p = new_context(browser, frame='ghost-frame')
        p.goto(base + '/profile.html')
        p.wait_for_selector('#big-av .band-avatar-inner', timeout=10000)
        ck('未知框 id 回退无框、头像仍在', not has(p, '#big-av canvas') and has(p, '#big-av .av-img'))
        ctx.close()

        # ---------- 4. 全站头像：个人栏 / 名册 / 他人主页 ----------
        ctx, p = new_context(browser, frame='autumn-wrap')
        p.goto(base + '/profile.html')
        p.wait_for_selector('#big-av canvas', timeout=10000)
        ck('侧栏个人头像也挂框', has(p, '.rail .me .ic span canvas'))
        p.goto(base + '/members.html')
        p.wait_for_selector('.mb-av canvas', timeout=10000)
        small = canvas_state(p, '.mb-av canvas')
        ck('名册小头像也挂框且画布是静态树', small and small['painted'] > 0, small)
        a = small['url']
        time.sleep(0.45)
        b = p.evaluate("() => document.querySelector('.mb-av canvas').toDataURL()")
        ck('小头像不跑落叶动画（两次快照一致）', a == b)
        p.goto(base + '/member-detail.html?id=1')
        p.wait_for_selector('#big-av canvas', timeout=10000)
        ck('他人主页大头像挂框', has(p, '#big-av canvas'))
        ctx.close()

        # ---------- 5. 减少动态效果：大头像用静帧，两次快照一致 ----------
        ctx, p = new_context(browser, frame='autumn-wrap', reduced=True)
        p.goto(base + '/profile.html')
        p.wait_for_selector('#big-av canvas', timeout=10000)
        p.wait_for_function("() => { const c=document.querySelector('#big-av canvas'); return c && c.width>0; }")
        a = p.evaluate("() => document.querySelector('#big-av canvas').toDataURL()")
        time.sleep(0.45)
        b = p.evaluate("() => document.querySelector('#big-av canvas').toDataURL()")
        ck('reduce 下大头像静帧不跳动', a == b)
        ctx.close()

        # 正常偏好下同一位置应有落叶变化（证明不是把动效整个关掉）
        ctx, p = new_context(browser, frame='autumn-wrap')
        p.goto(base + '/profile.html')
        p.wait_for_selector('#big-av canvas', timeout=10000)
        p.wait_for_function("() => { const c=document.querySelector('#big-av canvas'); return c && c.width>0; }")
        a = p.evaluate("() => document.querySelector('#big-av canvas').toDataURL()")
        time.sleep(0.45)
        b = p.evaluate("() => document.querySelector('#big-av canvas').toDataURL()")
        ck('正常偏好下大头像落叶在动（两次快照不同）', a != b)
        ctx.close()

        # ---------- 6. 手机宽度不横溢 ----------
        for width in (390, 320):
            ctx, p = new_context(browser, width=width, height=844, frame='autumn-wrap')
            p.goto(base + '/profile.html')
            p.wait_for_selector('#big-av canvas', timeout=10000)
            m = p.evaluate("""() => {
              const c = document.querySelector('#big-av canvas').getBoundingClientRect();
              return {left: c.left, right: c.right, vw: innerWidth,
                      scrollW: document.documentElement.scrollWidth};
            }""")
            ck(f'{width}px 预览框不超出视口、页面不横溢',
               m['left'] >= -1 and m['right'] <= m['vw'] + 1 and m['scrollW'] <= m['vw'] + 1, m)
            ctx.close()

        # ---------- 7. 头像文字层：有文案的显示在选择区名称下方，没文案的不占位 ----------
        ctx, p = new_context(browser, frame=None)
        p.goto(base + '/profile.html')
        p.wait_for_selector('#big-av .band-avatar-inner', timeout=10000)
        p.locator('#edit-btn').click()
        p.wait_for_selector('#pf-edit', state='visible')
        p.locator('#pn-tab-avatar').click()
        ck('当前头像（青蛙）无文案，说明行不显示', not vis(p, '#av-desc'))

        p.locator('[data-avatar-id="band-sunlit-mountains"]').click()
        ck('选「晴日云山」后显示文案「日光越过云海，远山依次醒来。」',
           vis(p, '#av-desc')
           and p.locator('#av-desc').inner_text().strip() == '日光越过云海，远山依次醒来。'
           and p.locator('#av-name').inner_text().strip() == '晴日云山')

        p.locator('[data-avatar-id="band-chubby"]').click()
        ck('选「黄胖胖」后显示文案「只是一只单纯的黄胖胖。」',
           vis(p, '#av-desc')
           and p.locator('#av-desc').inner_text().strip() == '只是一只单纯的黄胖胖。'
           and p.locator('#av-name').inner_text().strip() == '黄胖胖')

        p.locator('[data-avatar-id="fluent-cat"]').click()
        ck('切回无文案的头像后说明行收起（不残留上一个文案）',
           not vis(p, '#av-desc') and p.locator('#av-name').inner_text().strip() == '猫')
        ctx.close()

        browser.close()
    srv.shutdown()

    bad = [c for c in CHECKS if not c[1]]
    print(f"\n{len(CHECKS) - len(bad)}/{len(CHECKS)} 通过；页面报错 {len(ERRORS)}")
    if ERRORS:
        print('\n'.join(ERRORS[:10]))
    sys.exit(1 if bad or ERRORS else 0)


if __name__ == '__main__':
    main()
