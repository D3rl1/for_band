"""#247/#248 生产前端探针：个人扉页、来源校验、草稿保护和列表恢复。
运行：python -X utf8 tools/profile_navigation_probe.py
使用仓库 static 真文件和本机 Chrome，所有 API 由隔离夹具接管；不登录或读写真实团员数据。
依赖与现有 theme_probe 相同，从 design-samples/.deps-246 加载本机 Playwright。
证据输出至 gitignore 的 design-samples/；失败退出 1。
"""
import sys,json,threading,traceback,re
from pathlib import Path
from urllib.parse import urlsplit,parse_qs
HERE=Path(__file__).resolve().parent.parent/'design-samples'
import profile_navigation_fixture as f
from playwright.sync_api import sync_playwright
checks=[];errors=[];OUT=HERE/'shots-247248';OUT.mkdir(exist_ok=True)
def ck(name,ok,detail=None):
    checks.append({'name':name,'pass':bool(ok),'detail':detail});print(('OK ' if ok else 'FAIL ')+name+(f' | {detail}' if detail is not None else ''),flush=True)
def api(route):
    path=urlsplit(route.request.url).path[5:];q=parse_qs(urlsplit(route.request.url).query)
    if re.fullmatch(r'events/\d+',path):return f.respond(route,{'id':1,'title':'隔离活动长标题测试','date':'2030-11-04','startTime':'19:00','endTime':'21:00','kind':'REHEARSAL','status':'PLANNED','sectionNames':[]})
    if re.fullmatch(r'events/\d+/attendance',path):return f.respond(route,{'rows':[],'shouldAttend':0,'present':0,'absent':0,'leave':0})
    if re.fullmatch(r'events/\d+/pieces',path):return f.respond(route,[])
    if re.fullmatch(r'members/\d+',path):return f.respond(route,{**f.member,'birthday':'05-18'})
    if path=='me/profile-visits':return f.respond(route,{'list':[],'total':0})
    if path.endswith('attendance-summary'):return f.respond(route,{k:{'expected':0,'present':0,'leave':0,'absent':0,'rate':None} for k in ['rehearsal','sectionClass']})
    if path=='leave-requests':return f.respond(route,{'list':[],'total':0})
    if path=='attendance/sessions':
        n=int(q.get('page',['1'])[0]);return f.respond(route,{'list':[{'eventId':(n-1)*50+i,'title':'隔离出勤 '+str(i),'date':'2026-10-01','startTime':'19:00','kind':'REHEARSAL','mine':'PRESENT'} for i in range(1,51)],'total':100})
    if re.fullmatch(r'issues/\d+',path):return f.respond(route,{'id':1,'title':'隔离问题长标题测试','authorId':1,'authorName':'验证甲','status':'PENDING','content':'隔离正文','deviceInfo':'测试环境','createdAt':'2026-11-04T10:00:00','commentCount':0,'reactionCount':0,'comments':[],'canEdit':True,'canChangeStatus':True,'reactedByMe':False})
    if path=='issues' and route.request.method=='GET':
        f.requests.append({'path':path,'query':urlsplit(route.request.url).query,'method':'GET','body':None})
        return f.respond(route,{'list':[{'id':i,'title':'隔离问题 '+str(i),'status':'PENDING','lastActivityAt':'2026-11-04T10:00:00','commentCount':0,'reactionCount':0} for i in range(1,21)],'total':60})
    return f.api(route)
def ready(p,url):
    p.goto(url);p.wait_for_function('!!BAND.navigation && !!history.state?.bandNavigation');p.wait_for_timeout(400)
def cancel(p):
    p.locator('#cf-cancel').click();p.locator('#confirm-modal').wait_for(state='hidden');p.wait_for_timeout(100)
def yes(p):
    p.locator('#cf-ok').click();p.wait_for_timeout(400)
def link(p,href):
    p.evaluate('(href)=>{document.getElementById("test-link")?.remove();let a=document.createElement("a");a.id="test-link";a.href=href;a.textContent="测试来源链接";document.querySelector(".mbody").prepend(a)}',href)
    p.locator('#test-link').click();p.wait_for_function('!!history.state?.bandNavigation && !location.search.includes("_band_nav")');p.wait_for_timeout(400)
def meta(p):return p.evaluate('(()=>{const s=JSON.parse(sessionStorage.getItem("band.navigation.v1"));return s?.entries[history.state?.bandNavigation?.id]})()')
def save(p):
    p.locator('#save').click();p.wait_for_function('!editing && !document.getElementById("pn-fields").disabled',timeout=6000)
def run():
    server=f.ThreadingHTTPServer(('127.0.0.1',0),f.Handler);threading.Thread(target=server.serve_forever,daemon=True).start()
    base=f'http://127.0.0.1:{server.server_port}'
    with sync_playwright() as pw:
        b=pw.chromium.launch(executable_path=r'C:\Program Files\Google\Chrome\Application\chrome.exe',headless=True,args=['--disable-background-timer-throttling','--disable-renderer-backgrounding','--disable-backgrounding-occluded-windows'])
        c=b.new_context(viewport={'width':1280,'height':900},service_workers='block',reduced_motion='no-preference')
        c.add_init_script("localStorage.setItem('band_token','isolated-fixture');sessionStorage.setItem('band.welcome.done','1');")
        c.route('**/api/**',api);p=c.new_page();p.on('pageerror',lambda e:errors.append(str(e)))
        ready(p,base+'/profile.html');p.locator('#edit-btn').wait_for(state='visible')
        ck('个人扉页真实字段与零场',p.locator('#big-name').inner_text()=='验证甲' and p.locator('#big-joined').inner_text()=='2023' and p.locator('#showcase-n').inner_text()=='0')
        ck('公开生日只显示月日',p.locator('#big-birthday').inner_text()=='05-18')
        ck('本人独立布局且无多余返回',p.locator('.pn-preview').count()==1 and p.locator('.pf-card,.page-back').count()==0)
        p.screenshot(path=str(OUT/'profile-desktop.png'),full_page=True)
        p.locator('#edit-btn').click();p.locator('#f-intro').wait_for(state='visible')
        ck('桌面编辑旁置预览',p.locator('#pf-edit').bounding_box()['x']>p.locator('#pf-card').bounding_box()['x']+p.locator('#pf-card').bounding_box()['width']-1)
        ck('待机保存无环',not f.metrics(p,'#save')['visible'])
        before=len([r for r in f.requests if r['method']=='PUT']);p.locator('#save').click();p.wait_for_timeout(150)
        ck('没有改动不提交且留在编辑',len([r for r in f.requests if r['method']=='PUT'])==before and p.locator('#pf-edit').is_visible())
        ck('字段原限制',p.locator('#f-intro').get_attribute('maxlength')=='100' and p.locator('#f-contact').get_attribute('maxlength')=='128')
        p.locator('#f-intro').fill('实时签名\n第二行');ck('实时预览不请求保存',p.locator('#big-intro').inner_text()=='实时签名\n第二行' and not [r for r in f.requests if r['method']=='PUT'])
        p.locator('#edit-cancel').click();cancel(p);ck('取消离开保留草稿',p.locator('#f-intro').input_value()=='实时签名\n第二行' and p.locator('#pf-edit').is_visible())
        p.locator('#edit-cancel').click();yes(p);ck('放弃恢复服务端值及焦点',p.locator('#big-intro').inner_text()=='隔离资料' and p.locator('#edit-btn').evaluate('e=>e===document.activeElement'))
        p.locator('#edit-btn').click();p.locator('#pn-tab-intro').focus();p.keyboard.press('End')
        ck('分组键盘与ARIA',p.locator('#pn-tab-details').get_attribute('aria-selected')=='true' and p.locator('#pn-panel-intro').is_hidden())
        p.locator('#clear-bd').click();ck('清生日草稿预览',p.locator('#big-birthday').inner_text()=='未填' and p.locator('#undo-bd').is_visible())
        f.fail.add('PUT me/profile');p.locator('#save').click();p.wait_for_function('document.getElementById("save").dataset.bdPhase==="error"')
        req=[r for r in f.requests if r['method']=='PUT' and r['path']=='me/profile'];ck('清空生日兼容原payload',req[-1]['body']=={'clearBirthday':True},req[-1]['body'])
        ck('失败留稿且解锁',p.locator('#f-birthday').input_value()=='' and p.locator('#save-status').inner_text().startswith('保存失败') and p.locator('#pn-fields').is_enabled())
        f.fail.clear()
        def timed_out(route):route.abort('timedout')
        p.route('**/api/me/profile',timed_out);p.locator('#save').click();p.wait_for_function('document.getElementById("save").dataset.bdPhase==="error"');p.unroute('**/api/me/profile',timed_out)
        ck('网络超时保留草稿并解锁',p.locator('#f-birthday').input_value()=='' and p.locator('#pn-fields').is_enabled() and not f.metrics(p,'#save')['visible'])
        p.locator('#undo-bd').click();ck('清空可撤销',p.locator('#f-birthday').input_value()=='2003-05-18')
        p.locator('#clear-bd').click();save(p);ck('生日清空真实保存',p.locator('#big-birthday').inner_text()=='未填')
        p.locator('#edit-btn').click();p.locator('#f-intro').fill('  保存后的签名  ');p.locator('#pn-tab-details').click();p.locator('#f-contact').fill('  联系新值  ');p.locator('#f-birthday').fill('2000-01-02');save(p)
        req=[r for r in f.requests if r['method']=='PUT' and r['path']=='me/profile'];ck('只提交改动字段与trim',req[-1]['body']=={'intro':'保存后的签名','contact':'联系新值','birthday':'2000-01-02'},req[-1]['body'])
        p.locator('#edit-btn').click();p.locator('#pn-tab-avatar').click();p.locator('#av-grid button').nth(1).click();av=p.evaluate('picked');save(p)
        ck('头像来自全量共享清单',len(p.evaluate('BAND.avatarList'))+1==p.locator('#av-grid button').count() and p.locator('#big-av img').count()==1)
        p.locator('#edit-btn').click();p.locator('#pn-tab-avatar').click();p.locator('#av-grid button').first.click();save(p)
        req=[r for r in f.requests if r['method']=='PUT' and r['path']=='me/profile'];ck('清头像为null并首字回退',req[-1]['body']=={'avatar':None} and p.locator('#big-av').inner_text()=='验')
        p.locator('#edit-btn').click();p.locator('#f-intro').fill('锁定期间');f.hold.add('PUT me/profile');p.locator('#save').click();p.wait_for_timeout(300)
        lock=p.evaluate('({fields:document.getElementById("pn-fields").disabled,cancel:document.getElementById("edit-cancel").disabled,tabs:document.querySelectorAll("[data-pn-tab]:disabled").length})')
        ck('提交锁住字段分组与取消',lock=={'fields':True,'cancel':True,'tabs':3},lock)
        p.locator('#save').dispatch_event('click');p.wait_for_timeout(100);ck('重复提交防护',len(f.pending.get('PUT me/profile',[]))==1)
        route=f.pending.pop('PUT me/profile')[0];f.hold.clear();f.api(route);p.wait_for_function('!editing',timeout=6000)
        # Dirty navigation uses the same modal; browser unload uses native protection.
        p.locator('#edit-btn').click();p.locator('#f-intro').fill('导航草稿');link_target='/pieces.html'
        p.evaluate('(h)=>{let a=document.createElement("a");a.id="dirty-link";a.href=h;a.textContent="曲谱";document.querySelector(".pn-header").append(a)}',link_target)
        p.locator('#dirty-link').click();cancel(p);ck('跨页取消保留个人草稿',urlsplit(p.url).path=='/profile.html' and p.locator('#f-intro').input_value()=='导航草稿')
        dialogs=[]
        def dismiss(d):dialogs.append(d.type);d.dismiss()
        p.on('dialog',dismiss)
        try:p.reload(timeout=3000)
        except Exception:pass
        ck('刷新触发原生未保存保护','beforeunload' in dialogs and p.locator('#f-intro').input_value()=='导航草稿')
        p.remove_listener('dialog',dismiss);p.locator('#dirty-link').click();yes(p);p.wait_for_url('**/pieces.html*');ck('确认离开只需一次确认',urlsplit(p.url).path=='/pieces.html')
        f.role='HONORARY';ready(p,base+'/profile.html');ck('荣誉只读入口隐藏',p.locator('#edit-entry').is_hidden() and p.locator('#honorary-note').is_visible());f.role='ADMIN'
        f.profile.update(intro=None,contact=None,birthday=None,joinedAt=None);ready(p,base+'/profile.html')
        ck('空值不虚构年份或个人资料',p.locator('#big-intro').inner_text()=='留一句话，介绍此刻的自己。' and p.locator('#big-joined').inner_text()=='—' and p.locator('#big-birthday').inner_text()=='未填' and p.locator('#big-contact').inner_text()=='未填')
        f.profile.update(joinedAt='2023-09-01')
        # Fixture stress with maximum text, invalid avatar and all viewport/theme states.
        f.profile.update(name='验证长姓名'*4,intro='长签名'*33+'末',contact='A'*128,avatar='invalid-avatar')
        for width in [1280,390,320]:
            p.set_viewport_size({'width':width,'height':900});ready(p,base+'/profile.html');p.locator('#edit-btn').click();p.locator('#pn-tab-details').click()
            for night in [False,True]:
                p.evaluate('(v)=>BAND.appearance.setTheme(v?"dark":"light")',night);p.wait_for_timeout(350)
                ck(f'个人页无横向溢出 {width}/{night}',p.evaluate('document.documentElement.scrollWidth<=innerWidth') and p.locator('#pf-edit').bounding_box()['width']<=width)
            ck(f'个人字段长文与无效头像回退 {width}',p.locator('#big-av').inner_text()=='验' and p.locator('#f-contact').input_value()=='A'*128)
            p.screenshot(path=str(OUT/f'profile-edit-{width}.png'),full_page=True)
            p.locator('#edit-cancel').click()
        f.profile.update(name='验证甲',intro='隔离资料',contact='demo',avatar=None)
        p.set_viewport_size({'width':1280,'height':900})
        # Six routes: fixed fallback, real href, uniqueness and touch/focus.
        parents={'event-detail':'events','concert-detail':'concerts','member-detail':'members','issue-detail':'issues','piece-detail':'pieces','theme':'events'}
        for child,parent in parents.items():
            p.evaluate('sessionStorage.removeItem("band.navigation.v1")');ready(p,base+'/'+child+'.html'+('' if child=='theme' else '?id=1'))
            a=p.locator('[data-band-return]');ck(child+' 单返回与父链接',a.count()==1 and a.get_attribute('href')=='/'+parent+'.html',a.get_attribute('href'))
            ck(child+' 触区/名称/内容首行',a.bounding_box()['height']>=44 and bool(a.get_attribute('aria-label')) and p.locator('.mbody > .page-return-row').count()==1)
            a.focus();ck(child+' 键盘焦点',a.evaluate('e=>e===document.activeElement'))
        # Native anchor provenance and history list recovery (including refresh).
        p.set_viewport_size({'width':1280,'height':600});ready(p,base+'/issues.html');p.locator('input[value="PENDING"]').check();p.locator('#mine-chip').check();p.wait_for_timeout(300)
        p.evaluate('page=2;load()');p.wait_for_timeout(400);p.locator('#list a').last.scroll_into_view_if_needed();p.wait_for_timeout(150);p.evaluate('BAND.navigation.save()');oldscroll=p.evaluate('scrollY')
        p.locator('#list a').last.click();p.wait_for_url('**/issue-detail.html*');p.wait_for_function('!!document.querySelector("[data-band-return]")');p.wait_for_timeout(400)
        ck('普通点击移除内部参数且证明相邻历史',meta(p)['trusted'] and '_band_nav' not in p.url,meta(p))
        ck('问题来源真实链接',p.locator('[data-band-return]').get_attribute('href')=='/issues.html')
        p.reload();p.wait_for_selector('[data-band-return]');p.wait_for_timeout(300);ck('刷新保留安全来源',meta(p)['trusted'])
        p.locator('[data-band-return]').click();p.wait_for_url('**/issues.html');p.wait_for_timeout(500)
        ck('问题筛选/页码恢复',p.locator('input[value="PENDING"]').is_checked() and p.locator('#mine-chip').is_checked() and p.evaluate('page')==2)
        ck('问题滚动恢复',oldscroll>100 and abs(p.evaluate('scrollY')-oldscroll)<3,(oldscroll,p.evaluate('scrollY')))
        # Direct source different from fixed parent, and chain B -> C -> B -> A.
        ready(p,base+'/my-attendance.html');link(p,'/event-detail.html?id=1');ck('非父列表来源优先',p.locator('[data-band-return]').get_attribute('href')=='/my-attendance.html')
        link(p,'/piece-detail.html?id=1');ck('连续详情来源',p.locator('[data-band-return]').get_attribute('href')=='/event-detail.html?id=1')
        p.locator('[data-band-return]').click();p.wait_for_url('**/event-detail.html?id=1');p.wait_for_timeout(300);p.locator('[data-band-return]').click();p.wait_for_url('**/my-attendance.html');ck('连续两次返回无子页循环',urlsplit(p.url).path=='/my-attendance.html')
        # Security: explicit source values never permit privileged/action/external/self redirects.
        f.role='MEMBER'
        for value in ['https://evil.test/events.html','javascript:alert(1)','/login.html','/api/auth/logout','/admin-members.html','/piece-detail.html?id=1','/piece-detail.html?id=0','/pieces.html?token=secret&size=999999999']:
            ready(p,base+'/piece-detail.html?id=1&from='+__import__('urllib.parse',fromlist=['quote']).quote(value,safe=''))
            href=p.locator('[data-band-return]').get_attribute('href');ck('拒绝来源 '+value,href=='/pieces.html',href)
        f.role='ADMIN'
        # New tab copies sessionStorage but cannot carry adjacency proof.
        ready(p,base+'/pieces.html');p.evaluate('let a=document.createElement("a");a.id="popup";a.href="/piece-detail.html?id=1";a.target="_blank";a.textContent="新标签";document.querySelector(".mbody").prepend(a)')
        with p.expect_popup() as popup:p.locator('#popup').click()
        child=popup.value;child.wait_for_selector('[data-band-return]');child.wait_for_timeout(300)
        ck('新标签无历史证明',meta(child)['trusted']==False and child.locator('[data-band-return]').get_attribute('href')=='/pieces.html')
        child.locator('[data-band-return]').click();child.wait_for_url('**/pieces.html');ck('新标签安全链接回退',urlsplit(child.url).path=='/pieces.html');child.close()
        # Corrupt state and account owner isolation, no data/drafts in cache.
        p.evaluate('sessionStorage.setItem("band.navigation.v1","broken")');ready(p,base+'/piece-detail.html?id=1');ck('缓存损坏仍安全回退',p.locator('[data-band-return]').get_attribute('href')=='/pieces.html')
        data=p.evaluate('JSON.parse(sessionStorage.getItem("band.navigation.v1"))');ck('只存视图无业务/草稿',not any(k in json.dumps(data,ensure_ascii=False) for k in ['隔离正文','demo','avatar','birthday','contact','intro']))
        p.evaluate('const s=JSON.parse(sessionStorage.getItem("band.navigation.v1"));s.owner="someone-else";sessionStorage.setItem("band.navigation.v1",JSON.stringify(s))');ready(p,base+'/piece-detail.html?id=1');ck('用户缓存隔离',meta(p)['owner']=='1' and not meta(p)['trusted'])
        # Unsaved detail editors must cancel safely.
        ready(p,base+'/issue-detail.html?id=1');p.locator('.is-reply-form textarea').fill('回帖草稿');p.locator('[data-band-return]').click();cancel(p);ck('回帖取消返回保留草稿',p.locator('.is-reply-form textarea').input_value()=='回帖草稿');p.locator('[data-band-return]').click();yes(p);p.wait_for_url('**/issues.html')
        ready(p,base+'/theme.html');p.locator('input[value="high"]').locator('..').click();p.locator('[data-band-return]').click();cancel(p);ck('主题草稿取消返回仍保留',p.locator('input[value="high"]').is_checked())
        p.locator('[data-band-return]').click();yes(p);p.wait_for_url('**/events.html')
        # Source storage blocked: no exception; usable href fallback.
        blocked=b.new_context(viewport={'width':390,'height':900},service_workers='block');blocked.add_init_script("localStorage.setItem('band_token','isolated-fixture');Storage.prototype.setItem=new Proxy(Storage.prototype.setItem,{apply(t,s,a){if(s===sessionStorage)throw new Error('blocked');return Reflect.apply(t,s,a)}})");blocked.route('**/api/**',api)
        bp=blocked.new_page();bp.on('pageerror',lambda e:errors.append(str(e)));ready(bp,base+'/piece-detail.html?id=1');ck('存储不可用真实href有效',bp.locator('[data-band-return]').get_attribute('href')=='/pieces.html');bp.locator('[data-band-return]').click();bp.wait_for_url('**/pieces.html');blocked.close()
        for width in [320,390,1280]:
            p.set_viewport_size({'width':width,'height':900});ready(p,base+'/issue-detail.html?id=1');a=p.locator('[data-band-return]');ck(f'返回窄宽屏无遮挡 {width}',p.evaluate('document.documentElement.scrollWidth<=innerWidth') and a.bounding_box()['x']>=0 and a.bounding_box()['height']>=44);p.screenshot(path=str(OUT/f'return-{width}.png'),full_page=True)
        ck('实际页面无JS异常',not errors,errors)
        b.close();server.shutdown()

# 同一套夹具补充列表、模态编辑、主题与窄屏连续性。
s=sys.modules[__name__]
f=s.f;ck=s.ck;ready=s.ready;link=s.link;meta=s.meta;cancel=s.cancel;yes=s.yes
def contrast(p,selector):
    return p.locator(selector).evaluate('''e=>{const c=document.createElement('canvas'),cx=c.getContext('2d');c.width=c.height=1;const lum=color=>{cx.clearRect(0,0,1,1);cx.fillStyle=color;cx.fillRect(0,0,1,1);const [r,g,b]=[...cx.getImageData(0,0,1,1).data].slice(0,3).map(v=>v/255).map(v=>v<=.04045?v/12.92:((v+.055)/1.055)**2.4);return .2126*r+.7152*g+.0722*b;};let n=e,bg;while(n){bg=getComputedStyle(n).backgroundColor;if(bg!=='rgba(0, 0, 0, 0)'&&bg!=='transparent')break;n=n.parentElement;}let a=lum(getComputedStyle(e).color),b=lum(bg||'white');return (Math.max(a,b)+.05)/(Math.min(a,b)+.05);}''')
def edge_api(route):
    path=urlsplit(route.request.url).path[5:]
    if re.fullmatch(r'pieces/\d+',path):return f.respond(route,{'piece':f.piece,'attachments':[]})
    if re.fullmatch(r'concerts/\d+',path):return f.respond(route,{'concert':{'id':1,'name':'隔离演出','date':'2026-11-04','status':'PLANNED','statusLabel':'计划中'},'program':[],'cast':[],'photos':[]})
    if path=='events':return f.respond(route,{'list':[{'id':i,'title':'隔离活动 '+str(i),'date':'2026-10-01','startTime':'19:00','endTime':'21:00','kind':'SECTION_CLASS','status':'PLANNED'} for i in range(1,81)],'total':80})
    if path=='concerts':return f.respond(route,{'list':[{'id':i,'name':'隔离演出 '+str(i),'date':'2026-10-01','startTime':'19:00','status':'PLANNED'} for i in range(1,81)],'total':80})
    if path=='members':return f.respond(route,{'list':[{**f.member,'id':i,'name':'隔离成员 '+str(i)} for i in range(1,81)],'total':80})
    if path=='pieces':return f.respond(route,{'list':[{**f.piece,'id':i,'title':'隔离曲目 '+str(i)} for i in range(1,51)],'total':50})
    return s.api(route)
def run_edges():
    server=f.ThreadingHTTPServer(('127.0.0.1',0),f.Handler);threading.Thread(target=server.serve_forever,daemon=True).start();base=f'http://127.0.0.1:{server.server_port}'
    with s.sync_playwright() as pw:
        b=pw.chromium.launch(executable_path=r'C:\Program Files\Google\Chrome\Application\chrome.exe',headless=True)
        c=b.new_context(viewport={'width':1280,'height':700},service_workers='block',reduced_motion='reduce');c.add_init_script("localStorage.setItem('band_token','fixture');sessionStorage.setItem('band.welcome.done','1')");c.route('**/api/**',edge_api)
        p=c.new_page();p.on('pageerror',lambda e:s.errors.append(str(e)))
        for name in ['events','concerts','members','pieces','my-attendance']:
            ready(p,base+'/'+name+'.html')
            if name=='events':p.locator('#filters [data-k="SECTION_CLASS"]').click()
            if name=='members':p.locator('#section').select_option('1');p.locator('#status').select_option('ACTIVE')
            if name=='pieces':p.locator('#q').fill('隔离曲目');p.evaluate('filter="CANDIDATE";load()')
            if name=='my-attendance':p.locator('#sess-more').click();p.wait_for_function('sessions.length===100');p.evaluate('filter="APPROVED";sessFilter="PRESENT";renderFilters();renderReqs();renderSessFilters();renderSessions()')
            p.wait_for_timeout(500)
            selector={'events':'#groups a','concerts':'#groups a','members':'#mb-body a','pieces':'#groups a','my-attendance':'#sessions a'}[name]
            a=p.locator(selector).last;a.scroll_into_view_if_needed();p.wait_for_timeout(200);before=p.evaluate('scrollY');p.evaluate('BAND.navigation.save()');a.click();p.wait_for_selector('[data-band-return]');p.wait_for_timeout(300)
            ck(name+' 列表直接来源',p.locator('[data-band-return]').get_attribute('href')=='/'+name+'.html')
            p.locator('[data-band-return]').click();p.wait_for_url('**/'+name+'.html');p.wait_for_timeout(600)
            state=p.evaluate('(()=>{const e=JSON.parse(sessionStorage.getItem("band.navigation.v1")).entries[history.state.bandNavigation.id];return {view:e.view,scroll:e.scroll}})()')
            ck(name+' 长列表滚动恢复',before>100 and abs(p.evaluate('scrollY')-before)<4,[before,p.evaluate('scrollY')])
            if name=='events':ck('活动类型恢复',p.evaluate('filter')=='SECTION_CLASS')
            if name=='members':ck('名册声部/状态恢复',p.locator('#section').input_value()=='1' and p.locator('#status').input_value()=='ACTIVE')
            if name=='pieces':ck('曲谱分类/搜索恢复',p.evaluate('filter')=='CANDIDATE' and p.locator('#q').input_value()=='隔离曲目')
            if name=='my-attendance':ck('我的出勤筛选/已载页数恢复',p.evaluate('filter==="APPROVED" && sessFilter==="PRESENT" && sessions.length===100'))
        # Explicit source requiring fallback replace, kept query, no pushed child loop.
        ready(p,base+'/pieces.html?view=all');link(p,'/piece-detail.html?id=1');length=p.evaluate('history.length');p.evaluate('const s=JSON.parse(sessionStorage.getItem("band.navigation.v1"));s.entries[history.state.bandNavigation.id].trusted=false;sessionStorage.setItem("band.navigation.v1",JSON.stringify(s))');p.reload();p.wait_for_selector('[data-band-return]');p.locator('[data-band-return]').click();p.wait_for_url('**/pieces.html?view=all');ck('无历史证明回退替换不新增项',p.evaluate('history.length')==length)
        ready(p,base+'/piece-detail.html?id=1&from=%2Fissues.html%3Fpage%3D2%26token%3Dsecret');ck('来源必要查询保留/敏感参数裁剪',p.locator('[data-band-return]').get_attribute('href')=='/issues.html?page=2')
        # All dirty editors preserve inputs on cancel. Trigger the visible anchor handler
        # from DOM when an existing modal covers it; native unload is independently tested.
        # #240 起 BAND.noAttend 含 ADMIN（管理员不参与考勤）⇒ 请假表单只对团员 / 声部长 / 团长出现。
        # 曲谱 / 演出编辑仍按管理层；这条旧用例原先一路沿用 ADMIN，请假那格已随 #240 失效。
        # 按页面给角色，保持“草稿取消返回保留”的原意。
        for page,role,open_sel,field,value in [
            ('piece-detail','ADMIN','#d-edit','#f-title','未保存曲谱'),
            ('concert-detail','ADMIN','#edit','#f-name','未保存演出'),
            ('event-detail','MEMBER','#leave-body button','#leave-reason','未保存请假')]:
            f.role=role
            ready(p,base+'/'+page+'.html?id=1');p.locator(open_sel).click();p.locator(field).wait_for(state='visible');p.wait_for_timeout(100);p.locator(field).fill(value);p.evaluate('document.querySelector("[data-band-return]").click()');cancel(p);ck(page+' 草稿取消返回保留',p.locator(field).input_value()==value);p.evaluate('document.querySelector("[data-band-return]").click()');yes(p);p.wait_for_timeout(300)
        f.role='ADMIN'
        ready(p,base+'/issue-detail.html?id=1');p.locator('.is-status-panel textarea').fill('状态说明草稿');p.locator('[data-band-return]').click();cancel(p);ck('状态修改草稿保护',p.locator('.is-status-panel textarea').input_value()=='状态说明草稿');p.locator('[data-band-return]').click();yes(p)
        # Theme discard must preserve the independently applied dark mode.
        ready(p,base+'/theme.html');p.locator('#theme-hex').fill('#A52A56');p.locator('#theme-hex').dispatch_event('change');p.locator('label:has(input[name="contrast"][value="high"])').click();p.locator('#theme-apply').click();p.wait_for_timeout(350)
        ready(p,base+'/profile.html');p.locator('#edit-btn').click();p.locator('#pn-tab-details').click()
        for mode in ['light','dark']:
            p.evaluate('(m)=>BAND.appearance.setTheme(m)',mode);p.wait_for_timeout(500)
            colors=p.locator('#f-contact').evaluate('e=>{let s=getComputedStyle(e),r=getComputedStyle(document.documentElement);return {fg:s.color,bg:s.backgroundColor,ink:r.getPropertyValue("--ink").trim()}}')
            ck('个人页自定义高对比主题 '+mode,colors['fg']!=colors['bg'] and p.evaluate('document.documentElement.dataset.theme')==mode,colors)
            for selector in ['.pn-tags','.pn-kicker','#big-joined','#showcase-n']:
                ratio=contrast(p,selector);ck('个人页文字对比度 '+mode+'/'+selector,ratio>=4.49,round(ratio,2))
            p.screenshot(path=str(s.OUT/f'custom-profile-{mode}.png'),full_page=True)
        p.locator('#edit-cancel').click();link(p,'/theme.html');ratio=contrast(p,'[data-band-return]');ck('返回文字在自定义深色可读',ratio>=4.49,round(ratio,2));p.locator('label:has(input[name="contrast"][value="standard"])').click();p.locator('[data-band-return]').click();cancel(p);ck('主题取消保留草稿及独立夜间',p.locator('input[value="standard"]').is_checked() and p.evaluate('BAND.appearance.currentTheme()')=='dark');p.locator('[data-band-return]').click();yes(p);p.wait_for_url('**/profile.html');ck('主题放弃不撤销夜间',p.evaluate('BAND.appearance.currentTheme()')=='dark')
        ready(p,base+'/piece-detail.html?id=1&from=%2Fadmin-members.html')
        ck('管理员合法来源可用',p.locator('[data-band-return]').get_attribute('href')=='/admin-members.html')
        p.evaluate('window.dispatchEvent(new StorageEvent("storage",{key:"band_token",oldValue:"old",newValue:"new"}))')
        ck('跨标签账号改变立即清缓存与旧来源链接',p.evaluate('sessionStorage.getItem("band.navigation.v1")') is None and p.locator('[data-band-return]').get_attribute('href')=='/pieces.html')
        p.evaluate('BAND.navigation.ready({id:2,role:"MEMBER"})')
        ck('新账号不继承旧权限来源',meta(p)['owner']=='2' and p.locator('[data-band-return]').get_attribute('href')=='/pieces.html')
        ready(p,base+'/profile.html');p.set_viewport_size({'width':390,'height':700});p.locator('#edit-btn').click();p.locator('#pn-tab-avatar').click();p.locator('#av-grid button').last.scroll_into_view_if_needed();p.wait_for_timeout(300)
        rect=p.locator('#pf-card').bounding_box();ck('手机长头像网格滚动预览仍在屏内',rect['y']>=50 and rect['y']+rect['height']<350,rect)
        p.screenshot(path=str(s.OUT/'mobile-avatar-sticky.png'))
        ck('reduced沿用系统减少动态效果',p.evaluate('matchMedia("(prefers-reduced-motion:reduce)").matches'))
        ck('补充页面无JS异常',not s.errors,s.errors)
        b.close();server.shutdown()
def run_navigation_matrix():
    """每类返回页分别覆盖真实非父来源、刷新、新标签和外站上一项。"""
    server=f.ThreadingHTTPServer(('127.0.0.1',0),f.Handler);threading.Thread(target=server.serve_forever,daemon=True).start()
    base=f'http://127.0.0.1:{server.server_port}';outside=f'http://localhost:{server.server_port}'
    parents={'event-detail':'events','concert-detail':'concerts','member-detail':'members','issue-detail':'issues','piece-detail':'pieces','theme':'events'}
    with sync_playwright() as pw:
        b=pw.chromium.launch(executable_path=r'C:\Program Files\Google\Chrome\Application\chrome.exe',headless=True)
        c=b.new_context(viewport={'width':1280,'height':900},service_workers='block',reduced_motion='reduce')
        c.add_init_script("localStorage.setItem('band_token','isolated-fixture');sessionStorage.setItem('band.welcome.done','1');")
        c.route('**/api/**',api);p=c.new_page();p.on('pageerror',lambda e:errors.append(str(e)))
        for child,parent in parents.items():
            dest='/'+child+'.html'+('' if child=='theme' else '?id=1')
            ready(p,base+'/my-attendance.html');link(p,dest)
            ck(child+' 矩阵/非父来源与相邻历史',meta(p)['trusted'] and p.locator('[data-band-return]').get_attribute('href')=='/my-attendance.html')
            p.reload();p.wait_for_selector('[data-band-return]');p.wait_for_timeout(200)
            ck(child+' 矩阵/刷新后来源',p.locator('[data-band-return]').get_attribute('href')=='/my-attendance.html')
            p.locator('[data-band-return]').click();p.wait_for_url('**/my-attendance.html');ck(child+' 矩阵/返回不循环',urlsplit(p.url).path=='/my-attendance.html')
            p.evaluate('(href)=>{let a=document.createElement("a");a.id="matrix-popup";a.href=href;a.target="_blank";a.textContent="新标签";document.querySelector(".mbody").prepend(a)}',dest)
            with p.expect_popup() as popup:p.locator('#matrix-popup').click()
            tab=popup.value;tab.wait_for_selector('[data-band-return]');tab.wait_for_timeout(200)
            ck(child+' 矩阵/新标签无历史证明',not meta(tab)['trusted'] and tab.locator('[data-band-return]').get_attribute('href')=='/my-attendance.html')
            tab.locator('[data-band-return]').click();tab.wait_for_url('**/my-attendance.html');tab.close()
            ready(p,outside+'/events.html');link(p,base+dest)
            ck(child+' 矩阵/外站上一项安全回退',not meta(p)['trusted'] and p.locator('[data-band-return]').get_attribute('href')=='/'+parent+'.html')
            p.locator('[data-band-return]').click();p.wait_for_url('**/'+parent+'.html');ck(child+' 矩阵/回退留在本站',p.url.startswith(base+'/'))
        ready(p,base+'/piece-detail.html?id=1&from=%2Fadmin-members.html')
        cleared=p.evaluate('(()=>{BAND.clearToken();return {token:localStorage.getItem("band_token"),me:sessionStorage.getItem("band_me"),nav:sessionStorage.getItem("band.navigation.v1"),href:document.querySelector("[data-band-return]").getAttribute("href")}})()')
        ck('退出登录清身份/来源且不调用浏览器原生navigation',cleared=={'token':None,'me':None,'nav':None,'href':'/pieces.html'},cleared)
        b.close();server.shutdown()
if __name__=='__main__':
    try:
        run()
        run_edges()
        run_navigation_matrix()
    except Exception:
        traceback.print_exc();ck('suite completed',False)
    (HERE/'profile-navigation-results.json').write_text(json.dumps({'checks':checks,'errors':errors},ensure_ascii=False,indent=2),encoding='utf-8')
    print(f'TOTAL {sum(x["pass"] for x in checks)}/{len(checks)}',flush=True)
    if errors:print('PAGE ERRORS: '+json.dumps(errors,ensure_ascii=False),flush=True)
    sys.exit(0 if checks and all(x['pass'] for x in checks) and not errors else 1)
