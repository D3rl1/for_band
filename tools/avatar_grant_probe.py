"""#266 生产前端探针：限定头像的可见性、标识与保存；管理端发放 / 撤回 / 记录。

运行：python -X utf8 tools/avatar_grant_probe.py
使用仓库 static 真文件和本机 Chrome；API / 头像清单由隔离夹具接管，不登录、不读写真实团员数据。
夹具清单里有一张 `scope: "grant"` 的限定头像，生产 `manifest.json` 不被改动。
失败退出 1。
"""
import json
import sys
import threading
from pathlib import Path

HERE = Path(__file__).resolve().parent.parent / 'design-samples'
sys.path.insert(0, str(Path(__file__).resolve().parent))
sys.path.insert(0, str(HERE / '.deps-246'))
import profile_navigation_fixture as f
from playwright.sync_api import sync_playwright

checks, errors = [], []
ORIG_API = f.api

# 夹具清单：公共静态 / 公共动态 / 一张限定（动画素材复用已有文件，避免依赖未入库素材）
MANIFEST = {
    'avatars': [
        {'id': 'fixture-public-a', 'name': '公共甲', 'file': 'fluent-frog.svg'},
        {'id': 'fixture-public-b', 'name': '公共乙', 'file': 'fluent-cat.svg'},
        {'id': 'fixture-dynamic-c', 'name': '动态丙', 'file': 'band-sunlit-mountains.webp',
         'stillFile': 'band-sunlit-mountains-still.png', 'animated': True},
        {'id': 'fixture-limited-1', 'name': '纪念秋叶', 'file': 'band-sunlit-mountains.webp',
         'stillFile': 'band-sunlit-mountains-still.png', 'animated': True,
         'scope': 'grant', 'description': '发给参与十周年演出的团员'},
    ]
}

MEMBERS = [
    {'id': 1, 'name': '验证甲', 'account': 'fixture-a', 'sectionName': '二胡', 'role': 'MEMBER',
     'status': 'ACTIVE', 'activated': True},
    {'id': 2, 'name': '验证乙', 'account': 'fixture-b', 'sectionName': '大提琴', 'role': 'MEMBER',
     'status': 'ACTIVE', 'activated': True},
    {'id': 3, 'name': '验证丙', 'account': 'fixture-c', 'sectionName': '长笛', 'role': 'MEMBER',
     'status': 'PAUSED', 'activated': True},
]

STATE = {'grants': set(), 'grants_fail': False, 'records': [], 'grant_posts': [], 'revokes': [],
         'manifest': None, 'grant_batch': None}


def initial_records():
    return [
        {'memberId': 1, 'memberName': '验证甲', 'account': 'fixture-a',
         'avatarId': 'fixture-limited-1', 'batch': '十周年', 'note': '',
         'grantedBy': 9, 'grantedByName': '管理员', 'grantedAt': '2026-11-16T09:00:00',
         'revokedAt': None, 'revokeReason': None},
        {'memberId': 2, 'memberName': '验证乙', 'account': 'fixture-b',
         'avatarId': 'fixture-limited-1', 'batch': '十周年', 'note': '',
         'grantedBy': 9, 'grantedByName': '管理员', 'grantedAt': '2026-11-15T09:00:00',
         'revokedAt': '2026-11-15T10:00:00', 'revokeReason': '发错人'},
    ]


def respond(route, data=None, code=0):
    route.fulfill(status=200, content_type='application/json',
                  body=json.dumps({'code': code, 'message': 'ok' if code == 0 else '模拟错误',
                                   'data': data}, ensure_ascii=False))


def api(route):
    from urllib.parse import urlsplit, parse_qs
    req = route.request
    path = urlsplit(req.url).path
    if path == '/api/me/avatar-grants':
        if STATE['grants_fail']:
            route.fulfill(status=500, content_type='application/json',
                          body=json.dumps({'code': 10003, 'message': '模拟失败', 'data': None},
                                          ensure_ascii=False))
            return
        respond(route, [{'avatarId': g, 'batch': STATE['grant_batch']}
                        for g in sorted(STATE['grants'])])
        return
    if path == '/api/admin/avatar-grants':
        if req.method == 'POST':
            body = req.post_data_json or {}
            STATE['grant_posts'].append(body)
            avatar_id = body.get('avatarId')
            by_id = {r['memberId']: r for r in STATE['records'] if r['avatarId'] == avatar_id}
            granted, skipped, reactivated = [], [], []
            for member_id in body.get('memberIds') or []:
                member = next((m for m in MEMBERS if m['id'] == member_id), None)
                row = by_id.get(member_id)
                if row and row['revokedAt'] is None:
                    skipped.append(member_id)
                elif row:
                    row['revokedAt'] = None
                    row['revokeReason'] = None
                    reactivated.append(member_id)
                else:
                    STATE['records'].insert(0, {
                        'memberId': member_id,
                        'memberName': member['name'] if member else str(member_id),
                        'account': member['account'] if member else '',
                        'avatarId': avatar_id, 'batch': body.get('batch') or '',
                        'note': body.get('note') or '', 'grantedBy': 9,
                        'grantedByName': '管理员', 'grantedAt': '2026-11-16T12:00:00',
                        'revokedAt': None, 'revokeReason': None})
                    granted.append(member_id)
            respond(route, {'granted': granted, 'skipped': skipped, 'reactivated': reactivated})
            return
        if req.method == 'GET':
            q = parse_qs(urlsplit(req.url).query)
            avatar_filter = (q.get('avatarId') or [''])[0]
            rows = [r for r in STATE['records']
                    if not avatar_filter or r['avatarId'] == avatar_filter]
            respond(route, rows)
            return
    if path.startswith('/api/admin/avatar-grants/'):
        parts = path.split('/')
        # /api/admin/avatar-grants/{memberId}/{avatarId}
        member_id, avatar_id = int(parts[-2]), parts[-1]
        reason = (parse_qs(urlsplit(req.url).query).get('reason') or [''])[0]
        STATE['revokes'].append((member_id, avatar_id, reason))
        for row in STATE['records']:
            if row['memberId'] == member_id and row['avatarId'] == avatar_id:
                row['revokedAt'] = '2026-11-16T12:30:00'
                row['revokeReason'] = reason
        respond(route, {'avatarCleared': False})
        return
    # 成员名单给发放面板勾人用
    if path == '/api/members' and req.method == 'GET':
        respond(route, {'list': MEMBERS, 'total': len(MEMBERS)})
        return
    ORIG_API(route)


def ck(name, ok, detail=None):
    checks.append({'name': name, 'pass': bool(ok), 'detail': detail})
    print(('OK ' if ok else 'FAIL ') + name + (f' | {detail}' if detail is not None else ''), flush=True)


def has(page, sel):
    return page.locator(sel).count() > 0


def txt(page, sel):
    return page.locator(sel).inner_text() if has(page, sel) else ''


def vis(page, sel):
    return has(page, sel) and page.locator(sel).is_visible()


def click(page, sel):
    if not has(page, sel):
        return False
    page.locator(sel).click()
    page.wait_for_timeout(80)
    return True


def route_manifest(ctx):
    def handler(route):
        route.fulfill(status=200, content_type='application/json',
                      body=json.dumps(STATE['manifest'] or MANIFEST, ensure_ascii=False))
    ctx.route('**/vendor/avatars/manifest.json', handler)


def open_edit(page):
    page.locator('#edit-btn').click()
    page.locator('#pn-tab-avatar').wait_for(state='visible')
    click(page, '#pn-tab-avatar')


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

        def context(grants=(), role='MEMBER', avatar=None, width=1280, height=900,
                    limited=True, records=None, grant_batch=None):
            STATE['grants'] = set(grants)
            STATE['grants_fail'] = False
            STATE['grant_batch'] = grant_batch
            STATE['manifest'] = MANIFEST if limited else {
                'avatars': [a for a in MANIFEST['avatars'] if a.get('scope') != 'grant']}
            STATE['records'] = initial_records() if records is None else records
            f.role = role
            f.profile['avatar'] = avatar
            f.profile['avatarFrame'] = None
            f.requests.clear()
            STATE['grant_posts'].clear()
            STATE['revokes'].clear()
            ctx = browser.new_context(viewport={'width': width, 'height': height},
                                      service_workers='block',
                                      reduced_motion='no-preference')
            ctx.add_init_script("localStorage.setItem('band_token','isolated-fixture');"
                                "sessionStorage.setItem('band.welcome.done','1');")
            ctx.route('**/api/**', api)
            route_manifest(ctx)
            page = ctx.new_page()
            page.on('pageerror', lambda e: errors.append(str(e)))
            return ctx, page

        def ready(page, page_name='profile.html'):
            page.goto(base + '/' + page_name)
            page.wait_for_function('!!BAND.navigation && !!history.state?.bandNavigation')
            page.wait_for_timeout(400)

        # ---------- A266-01 个人网格：未获赠不出现 ----------
        ctx, p = context(grants=())
        ready(p)
        open_edit(p)
        ck('未获赠：限定头像不出现在网格',
           not has(p, '[data-avatar-id="fixture-limited-1"]'))
        ck('未获赠：公共静态 / 动态头像照常显示',
           has(p, '[data-avatar-id="fixture-public-a"]') and has(p, '[data-avatar-id="fixture-dynamic-c"]'))
        ctx.close()

        # ---------- A266-02 个人网格：获赠后出现且带标识 ----------
        ctx, p = context(grants={'fixture-limited-1'}, grant_batch='十周年')
        ready(p)
        open_edit(p)
        cell = p.locator('[data-avatar-id="fixture-limited-1"]')
        ck('获赠：限定头像出现在网格', cell.count() == 1)
        ck('获赠：格子内有「限定」角标',
           cell.locator('.av-limit').count() == 1
           and '限定' in (cell.locator('.av-limit').inner_text() if cell.count() else ''))
        ck('获赠：aria-label 说清是纪念限定',
           '纪念限定' in ((cell.get_attribute('aria-label') if cell.count() else '') or ''))
        ck('获赠：限定项仍只在动态组（不另开第三组）',
           '限定' not in txt(p, '#av-grid > .av-group:nth-child(1)'))

        # ---------- A266-03 保存限定头像 ----------
        click(p, '[data-avatar-id="fixture-limited-1"]')
        ck('选中限定头像时点名「纪念限定」', '纪念限定' in txt(p, '#av-name'))
        ck('选中区显示纪念批次', '（十周年）' in txt(p, '#av-name'))
        p.locator('#save').click()
        p.wait_for_function('!editing', timeout=6000)
        put = [r for r in f.requests if r['method'] == 'PUT' and r['path'] == 'me/profile'][-1]
        ck('保存限定头像只传 avatar id',
           put['body'] == {'avatar': 'fixture-limited-1'}, put['body'])
        open_edit(p)
        ck('保存后回显限定头像', p.evaluate('picked') == 'fixture-limited-1')
        ctx.close()

        # ---------- A266-04 资格接口失败时失败即隐藏 ----------
        ctx, p = context(grants={'fixture-limited-1'})
        STATE['grants_fail'] = True
        ready(p)
        open_edit(p)
        ck('资格接口失败：公共头像仍可用',
           has(p, '[data-avatar-id="fixture-public-a"]') and has(p, '[data-avatar-id="fixture-dynamic-c"]'))
        ck('资格接口失败：限定头像不出现（失败即隐藏）',
           not has(p, '[data-avatar-id="fixture-limited-1"]'))
        ck('资格接口失败：页面无 JS 报错', not errors, errors[:3])
        ctx.close()

        # ---------- A266-05 管理端入口 ----------
        # 非管理员在这页拿不到导航（菜单里没有它），所以用宽松等待：只要入口不出现即可。
        ctx, p = context(role='MEMBER')
        p.goto(base + '/admin-members.html')
        p.wait_for_timeout(1500)
        ck('非管理员看不到发放入口', not vis(p, '#grant-btn'))
        ctx.close()

        ctx, p = context(role='ADMIN')
        ready(p, 'admin-members.html')
        ck('管理员可见发放入口', vis(p, '#grant-btn'))
        click(p, '#grant-btn')
        p.locator('#grant-modal').wait_for(state='visible')
        ck('面板只列限定头像',
           p.locator('#grant-avatar option').count() == 1 and '纪念秋叶' in txt(p, '#grant-avatar'))
        ck('面板列出可勾选成员', p.locator('#grant-people [data-grant-member]').count() == 3)
        ck('已拥有者单独提示', p.locator('#grant-people [data-own="1"]').count() >= 1)

        # ---------- A266-06 发放记录（含已撤回） ----------
        p.wait_for_function("document.querySelectorAll('#grant-records .grant-rec').length>=2", timeout=6000)
        recs = txt(p, '#grant-records')
        ck('记录含有效与已撤回两行', p.locator('#grant-records .grant-rec').count() == 2)
        ck('已撤回行显示状态与理由', '已撤回' in recs and '发错人' in recs)

        # ---------- A266-07 撤回：理由必填，只标记不删行 ----------
        click(p, '#grant-records .grant-rec[data-mid="1"] .grant-revoke')
        ck('撤回先展开理由输入', vis(p, '#grant-records .grant-rec[data-mid="1"] .grant-reason'))
        click(p, '#grant-records .grant-rec[data-mid="1"] .grant-revoke-ok')
        p.wait_for_timeout(200)
        ck('理由为空不发撤回请求', not STATE['revokes'])
        p.fill('#grant-records .grant-rec[data-mid="1"] .grant-reason', '发错人了')
        click(p, '#grant-records .grant-rec[data-mid="1"] .grant-revoke-ok')
        p.wait_for_function("document.querySelector('#grant-records').textContent.includes('发错人了')",
                            timeout=6000)
        ck('撤回请求带理由且只针对该行',
           STATE['revokes'][-1] == (1, 'fixture-limited-1', '发错人了'), STATE['revokes'])
        ck('撤回后该行标为已撤回', '已撤回' in txt(p, '#grant-records'))
        ck('记录行未被删除', p.locator('#grant-records .grant-rec').count() == 2)

        # ---------- A266-08 批量发放 ----------
        p.fill('#grant-batch', '十周年')
        p.locator('#grant-people [data-grant-member="2"]').check()
        p.locator('#grant-people [data-grant-member="3"]').check()
        ck('已选人数随勾选更新', '已选 2 人' in txt(p, '#grant-count'))
        click(p, '#grant-submit')
        p.wait_for_function("!document.getElementById('grant-result').hidden", timeout=6000)
        ck('发放请求体正确',
           STATE['grant_posts'][-1] == {'avatarId': 'fixture-limited-1',
                                        'memberIds': [2, 3], 'batch': '十周年'},
           STATE['grant_posts'][-1] if STATE['grant_posts'] else None)
        res = txt(p, '#grant-result')
        ck('结果按新发放 / 重新发放分开报数', '新发放 1' in res and '重新发放 1' in res, res)
        ck('管理端页面无 JS 报错', not errors, errors[:3])
        ctx.close()

        # ---------- A266-09 没有限定头像时的空态 ----------
        ctx, p = context(role='ADMIN', limited=False)
        ready(p, 'admin-members.html')
        click(p, '#grant-btn')
        p.locator('#grant-modal').wait_for(state='visible')
        ck('没限定头像时显示空态说明', vis(p, '#grant-empty'))
        ck('没限定头像时禁止发放', p.locator('#grant-submit').is_disabled())
        ctx.close()

    total = len(checks)
    passed = sum(1 for c in checks if c['pass'])
    print(f'\n===== {passed}/{total} =====')
    if errors:
        print('页面报错：', errors[:5])
    return 0 if passed == total and not errors else 1


if __name__ == '__main__':
    sys.exit(run())
