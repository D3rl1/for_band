"""#249 隔离前端验收：使用真实 static 文件与虚构接口，不登录真实账号。
运行 python -X utf8 tools/paused_member_probe.py；本机 Playwright 可放 design-samples/.deps-246。
截图和 JSON 证据输出至已忽略的 design-samples/shots-249。
"""
import json
import sys
import threading
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import urlsplit

ROOT = Path(__file__).resolve().parent.parent
try:
    from playwright.sync_api import sync_playwright
except ImportError:
    sys.path.insert(0, str(ROOT / 'design-samples/.deps-246'))
    from playwright.sync_api import sync_playwright

OUT = ROOT / 'design-samples/shots-249'
OUT.mkdir(parents=True, exist_ok=True)
STATIC = ROOT / 'src/main/resources/static'
checks, errors, writes = [], [], []
person = dict(id=2, name='隔离验证甲', account='fixture249', role='MEMBER', status='PAUSED',
              sectionId=1, sectionName='验证声部', joinedAt='2020-01-01', isSystem=False, activated=True)
state = dict(me=person, fail=False, assigned=True)
assignment = dict(id=10, sectionId=1, sectionName='验证声部', title='隔离练习', pieceId=None,
                  pieceTitle=None, linked=False, submittedCount=0, canDelete=True)


def ck(name, ok):
    checks.append(dict(name=name, passed=bool(ok)))
    print(('OK ' if ok else 'FAIL ') + name, flush=True)


class Handler(SimpleHTTPRequestHandler):
    def __init__(self, *args, **kw):
        super().__init__(*args, directory=str(STATIC), **kw)
    def log_message(self, *_):
        pass


def respond(route, data=None, code=0):
    route.fulfill(status=200, content_type='application/json', body=json.dumps(
        dict(code=code, message='隔离加载失败' if code else 'ok', data=data), ensure_ascii=False))


def api(route):
    path = urlsplit(route.request.url).path[5:]
    if route.request.method in ('POST', 'PUT', 'DELETE'):
        writes.append(dict(path=path, method=route.request.method, body=route.request.post_data))
        return respond(route, {})
    if path == 'auth/me': return respond(route, state['me'])
    if path == 'avatars': return respond(route, json.loads((STATIC/'vendor/avatars/manifest.json').read_text(encoding='utf-8'))['avatars'])
    if path in ('birthdays/today', 'me/recent-activity', 'recordings/manage/weeks'): return respond(route, [])
    if path in ('release-notes/latest', 'me/section-note-pending'): return respond(route, None)
    if path == 'issues/activity': return respond(route, dict(latestUpdatedAt=None))
    if path == 'sections': return respond(route, [dict(id=1, name='验证声部')])
    if path == 'members': return respond(route, dict(list=[
        {**person, 'id':2, 'status':'ACTIVE'}, {**person, 'id':3, 'name':'隔离休团乙'},
        {**person, 'id':4, 'name':'隔离退团丙', 'status':'LEFT'},
        {**person, 'id':5, 'name':'隔离荣誉丁', 'status':'GRADUATED', 'role':'HONORARY', 'honoraryUntil':'2099-12-31'}], total=4))
    if path == 'recordings/assignments/current':
        return respond(route, [] if not state['assigned'] else [assignment], 10003 if state['fail'] else 0)
    if path == 'recordings/manage': return respond(route, dict(current=True, weekStart='2026-11-02', weekEnd='2026-11-08',
        assignments=[assignment], sections=[dict(sectionId=1, sectionName='验证声部', members=[
            dict(memberId=2, name='隔离休团甲', state=None, doneCount=0, totalCount=0, unlinkedCount=0, recordings=[]),
            dict(memberId=3, name='隔离在团乙', state='MISS', doneCount=0, totalCount=1, unlinkedCount=0, recordings=[])])]))
    if path in ('pieces', 'recordings', 'leave-requests', 'attendance/sessions'): return respond(route, dict(list=[], total=0))
    if path == 'events/1': return respond(route, dict(id=1, title='隔离活动', date='2099-11-06', startTime='19:00', endTime='21:00', kind='REHEARSAL', status='NORMAL', sectionName=None, myAttendanceStatus=None))
    if path == 'events/1/attendance': return respond(route, dict(rows=[], expected=0, present=0, leave=0, absent=0, rate=0))
    if path == 'events/1/pieces': return respond(route, [])
    if path.endswith('attendance-summary'): return respond(route, dict(rehearsal=dict(expected=1,present=1,leave=0,absent=0,rate=1),sectionClass=dict(expected=0,present=0,leave=0,absent=0,rate=0)))
    return respond(route, {})


def run():
    server = ThreadingHTTPServer(('127.0.0.1', 0), Handler)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    base = f'http://127.0.0.1:{server.server_port}'
    with sync_playwright() as pw:
        browser = pw.chromium.launch(executable_path=r'C:\Program Files\Google\Chrome\Application\chrome.exe', headless=True)
        context = browser.new_context(viewport=dict(width=1280,height=900), service_workers='block', reduced_motion='reduce')
        context.add_init_script("localStorage.setItem('band_token','isolated249');sessionStorage.setItem('band.welcome.done','1');")
        context.route('**/api/**', api)
        page = context.new_page()
        page.on('pageerror', lambda e: errors.append(str(e)))
        def ready(url):
            page.goto(base+url)
            page.wait_for_function("typeof BAND !== 'undefined' && !!BAND.me")
            page.wait_for_timeout(500)
        ready('/recordings.html')
        page.locator('#week-note').wait_for(state='visible')
        ck('休团有声部有作业仍免交', page.locator('#week-note').inner_text() == '休团期间免交录音作业')
        ck('免交无必交清单与完成率', page.locator('#week-list').inner_text() == '' and page.locator('#week-sum').inner_text() == '' and page.locator('[data-week-go]').count() == 0)
        ck('自由录音与自愿关联仍可用', page.locator('#go-record').is_visible() and not page.locator('#upload-box').evaluate('e=>e.hidden') and page.locator('#u-assign').is_enabled())
        page.locator('#go-record').click()
        page.locator('#u-title').fill('尚未上传的名称')
        page.evaluate('renderWeekCard()')
        ck('免交重绘保留未保存名称', page.locator('#u-title').input_value() == '尚未上传的名称')
        page.locator('#dback').click()
        for width, theme in [(1280,'light'),(1280,'dark'),(390,'light'),(390,'dark'),(320,'light'),(320,'dark')]:
            page.set_viewport_size(dict(width=width,height=900))
            page.evaluate('(t)=>document.documentElement.dataset.theme=t', theme)
            page.locator('#week-card').scroll_into_view_if_needed()
            ck(f'{width}px {theme} 免交卡无溢出', page.locator('#week-card').evaluate('e=>e.scrollWidth<=e.clientWidth+1'))
            page.screenshot(path=str(OUT/f'recordings-{width}-{theme}.png'),full_page=True)
        state['fail'] = True
        ready('/recordings.html')
        ck('作业失败仍与休团免交区分', page.locator('#week-note').inner_text() == '休团期间免交录音作业' and '加载失败' in page.locator('#u-hint').inner_text())
        state['me'] = {**person,'status':'ACTIVE'}
        ready('/recordings.html')
        ck('在团加载失败不冒充免交', '加载失败' in page.locator('#week-note').inner_text())
        state['fail'] = False
        state['me'] = {**person,'status':'ACTIVE','sectionId':None}
        ready('/recordings.html')
        ck('无声部空态独立', '没有所属声部' in page.locator('#week-note').inner_text())
        state['me'] = {**person,'status':'ACTIVE'}
        state['assigned'] = False
        ready('/recordings.html')
        ck('未布置空态独立', page.locator('#week-note').inner_text() == '本周未布置作业。')
        state['assigned'] = True
        state['me'] = person
        ready('/event-detail.html?id=1')
        ck('休团活动无本人签到请假', '休团期间免出勤' in page.locator('#checkin-body').inner_text() and '休团期间免出勤' in page.locator('#leave-body').inner_text() and page.locator('#checkin-body button,#leave-body button').count() == 0)
        state['me'] = {**person,'role':'LEADER'}
        ready('/recordings.html')
        ck('休团团长保留管理视图', page.locator('#manage-pane').is_visible())
        ck('管理分母只数适用成员', page.locator('#rm-hero .stat .n').all_inner_texts() == ['1','1','0','0'])
        state['me'] = {**person,'id':1,'role':'ADMIN','status':'ACTIVE','isSystem':True}
        ready('/admin-members.html')
        active = page.locator('tr[data-mid="2"]')
        active.get_by_role('button',name='设为休团',exact=True).click()
        page.locator('#confirm-modal').wait_for(state='visible')
        ck('单人确认不称停用或吊销', '仍可登录' in page.locator('.cf-body').inner_text() and '吊销' not in page.locator('.cf-body').inner_text())
        page.screenshot(path=str(OUT/'admin-confirm-390.png'),full_page=True)
        page.locator('#cf-cancel').click()
        active.get_by_role('button',name='编辑',exact=True).click()
        page.locator('#edit-status').select_option('PAUSED')
        page.locator('#edit-save').click()
        page.locator('#confirm-modal').wait_for(state='visible')
        ck('编辑休团说明同口径', '仍可登录' in page.locator('.cf-body').inner_text())
        page.locator('#cf-cancel').click()
        page.locator('#edit-modal [data-bs-dismiss="modal"]').last.click()
        page.locator('#edit-modal').wait_for(state='hidden')
        page.locator('#bulk-btn').click()
        for member_id in (2,3,4,5): page.locator(f'tr[data-mid="{member_id}"] .t-pick input').check()
        page.locator('#bulk-stop').click()
        page.locator('#confirm-modal').wait_for(state='visible')
        ck('批量只计在团且说明保留登录', '1 人' in page.locator('.cf-title').inner_text() and '仍可登录' in page.locator('.cf-body').inner_text())
        page.locator('#cf-ok').click()
        page.wait_for_timeout(350)
        changes = [w for w in writes if w['method']=='PUT' and w['path'].startswith('admin/members/')]
        ck('批量不改退团毕业或荣誉身份', len(changes)==1 and changes[0]['path']=='admin/members/2')
        ck('页面脚本无错误', not errors)
        context.close(); browser.close()
    server.shutdown()


if __name__ == '__main__':
    try:
        run()
    finally:
        (OUT/'results.json').write_text(json.dumps(dict(checks=checks, errors=errors),ensure_ascii=False,indent=2),encoding='utf-8')
    raise SystemExit(0 if checks and all(c['passed'] for c in checks) and not errors else 1)
