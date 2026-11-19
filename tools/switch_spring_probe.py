"""#251 生产共用开关与问题筛选的隔离逐帧验收。
python -X utf8 tools/switch_spring_probe.py
接口均为虚构夹具；输出 design-samples/shots-251/{results,frames}.json 与截图。
"""
import json
import sys
import threading
from pathlib import Path
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlsplit, parse_qs

ROOT = Path(__file__).resolve().parent.parent
try:
    from playwright.sync_api import sync_playwright
except ImportError:
    sys.path.insert(0, str(ROOT / 'design-samples/.deps-246'))
    from playwright.sync_api import sync_playwright

STATIC = ROOT / 'src/main/resources/static'
OUT = ROOT / 'design-samples/shots-251'
OUT.mkdir(parents=True, exist_ok=True)
checks, errors, requests, traces = [], [], [], {}
held = []
hold_next = False


def ck(name, passed, detail=None):
    checks.append(dict(name=name, passed=bool(passed), detail=detail))
    print(('OK ' if passed else 'FAIL ') + name + (f' | {detail}' if detail is not None else ''), flush=True)


class Handler(SimpleHTTPRequestHandler):
    def __init__(self, *a, **kw): super().__init__(*a, directory=str(STATIC), **kw)
    def log_message(self, *_): pass


def respond(route, data):
    route.fulfill(status=200, content_type='application/json', body=json.dumps(dict(code=0, message='ok', data=data)))


def api(route):
    global hold_next
    path = urlsplit(route.request.url).path[5:]
    q = parse_qs(urlsplit(route.request.url).query)
    if path == 'auth/me': return respond(route, dict(id=1,name='隔离验证',role='MEMBER',status='ACTIVE',sectionId=1,sectionName='验证声部',isSystem=False))
    if path == 'issues' and route.request.method == 'GET':
        requests.append(q)
        if hold_next:
            hold_next = False; held.append(route); return
        return respond(route, dict(list=[],total=60))
    if path == 'issues/activity': return respond(route, dict(latestUpdatedAt=None))
    if path in ('release-notes/latest','me/section-note-pending'): return respond(route,None)
    if path in ('birthdays/today','me/recent-activity','sections'): return respond(route,[])
    return respond(route,{})


CAPTURE = r"""(options)=>{
 const {selector='#mine-chip',ms=1500,arm=true}=options||{};
 const input=document.querySelector(selector), label=input.closest('label');
 const rows=[];let start;
 window.swDone=false;window.swFrames=rows;
 function sample(t){
  const p=getComputedStyle(input,'::before'),r=input.getBoundingClientRect(),s=label.querySelector('span')?.getBoundingClientRect();
  const x=p.transform==='none'?0:new DOMMatrixReadOnly(p.transform).m41;
  const left=parseFloat(p.left)+x, width=parseFloat(p.width);
  rows.push({t:t-start,x,checked:input.checked,track:[r.x,r.y,r.width,r.height],text:s?[s.x,s.y,s.width,s.height]:null,inTrack:left>=-.05 && left+width<=input.clientWidth+.05});
  const rest=input.checked?(parseFloat(getComputedStyle(input).getPropertyValue('--band-switch-travel'))||14):0;
  const settled=rows.length>=5 && rows.slice(-5).every(v=>Math.abs(v.x-rest)<.0001);
  if(t-start<ms || (!settled && t-start<ms+4000))requestAnimationFrame(sample);else window.swDone=true;
 }
 function begin(){start=performance.now();sample(start);}
 if(arm)input.addEventListener('change',begin,{once:true});else begin();
}"""


def finish(page, key):
    try:
        page.wait_for_function('window.swDone', timeout=20000)
    except Exception:
        traces[key] = page.evaluate('window.swFrames || []')
        print('CAPTURE TIMEOUT '+key+' '+str(page.evaluate('({hidden:document.hidden,frames:swFrames.length,last:swFrames.at(-1)?.t})')),flush=True)
        raise
    rows = page.evaluate('window.swFrames')
    traces[key] = rows
    return rows


def metrics(rows):
    return dict(min=round(min(r['x'] for r in rows),3),max=round(max(r['x'] for r in rows),3),last=round(rows[-1]['x'],3))


def stable(rows):
    first=rows[0]
    return all(all(abs(a-b)<.05 for a,b in zip(r['track'],first['track'])) and r['text']==first['text'] and r['inTrack'] for r in rows)


def read_contrast(page):
    return page.locator('#mine-chip').evaluate(r"""e=>{
      const rgb=c=>(c.match(/[\d.]+/g)||[]).slice(0,3).map(Number);
      const lum=c=>rgb(c).map(v=>{v/=255;return v<=.04045?v/12.92:((v+.055)/1.055)**2.4}).reduce((n,v,i)=>n+v*[.2126,.7152,.0722][i],0);
      const ratio=(a,b)=>{a=lum(a);b=lum(b);return (Math.max(a,b)+.05)/(Math.min(a,b)+.05)};
      const bg=document.createElement('i');bg.style.background='var(--bg)';document.body.append(bg);
      const color=getComputedStyle(bg).backgroundColor;bg.remove();
      const s=getComputedStyle(e),p=getComputedStyle(e,'::before');
      return {text:ratio(getComputedStyle(e.closest('label')).color,color),edge:ratio(s.borderTopColor,color),thumb:ratio(p.backgroundColor,s.backgroundColor)};
    }""")


def run():
    global hold_next
    server=ThreadingHTTPServer(('127.0.0.1',0),Handler)
    threading.Thread(target=server.serve_forever,daemon=True).start()
    base=f'http://127.0.0.1:{server.server_port}'
    with sync_playwright() as pw:
        browser=pw.chromium.launch(executable_path=r'C:\Program Files\Google\Chrome\Application\chrome.exe',headless=True,
                                  args=['--disable-background-timer-throttling','--disable-renderer-backgrounding','--disable-backgrounding-occluded-windows'])
        ctx=browser.new_context(viewport=dict(width=1280,height=900),service_workers='block',reduced_motion='no-preference')
        ctx.add_init_script("localStorage.setItem('band_token','isolated251');sessionStorage.setItem('band.welcome.done','1');")
        ctx.route('**/api/**',api)
        page=ctx.new_page();page.on('pageerror',lambda e:errors.append(str(e)))
        def ready():
            page.bring_to_front()
            page.goto(base+'/issues.html');page.wait_for_function("typeof BAND!=='undefined' && !!BAND.me && typeof BAND.switch==='function'")
            page.wait_for_timeout(450)
            page.evaluate("window.changeCount=0;document.getElementById('mine-chip').addEventListener('change',()=>changeCount++);")
        ready()
        ck('默认原生 switch 与可访问名称',page.get_by_role('switch',name='我提的问题').count()==1 and not page.locator('#mine-chip').is_checked())
        page.evaluate(CAPTURE,dict(ms=250,arm=False));rows=finish(page,'initial')
        ck('首帧不弹',all(r['x']==0 for r in rows))
        before=len(requests)
        page.evaluate(CAPTURE)
        page.locator('label:has(#mine-chip) span').click()
        rows=finish(page,'label-on');m=metrics(rows)
        ck('开启双向之一可见越过并收敛',m['max']>15 and abs(m['last']-14)<.02,m)
        ck('开启轨道文字与触区固定且不越轨',stable(rows))
        ck('标签一次 change 与一次筛选请求',page.evaluate('changeCount')==1 and len(requests)==before+1 and requests[-1].get('mine')==['1'])
        page.locator('#mine-chip').focus();page.evaluate(CAPTURE)
        page.keyboard.press('Space');rows=finish(page,'space-off');m=metrics(rows)
        ck('关闭双向另一侧越过并收敛',m['min']<-1 and abs(m['last'])<.02,m)
        ck('关闭轨道文字与触区固定且不越轨',stable(rows))
        ck('空格一次 change 且 checked 同步',page.evaluate('changeCount')==2 and not page.locator('#mine-chip').is_checked() and 'mine' not in requests[-1])
        # 原页面快速加载完成会调用 syncFilters；同值同步不得抹掉弹簧。
        page.evaluate(CAPTURE)
        page.evaluate("(()=>{const e=document.getElementById('mine-chip');e.click();setTimeout(()=>{for(let i=0;i<20;i++)BAND.switch(e).setChecked(e.checked)},80)})()")
        rows=finish(page,'same-value-echo');m=metrics(rows)
        ck('即时及晚到同值回显不掐断运动',m['max']>15 and m['last']==14,m)
        page.evaluate("BAND.switch(document.getElementById('mine-chip')).setChecked(false)")
        page.evaluate(CAPTURE)
        page.evaluate("(()=>{const e=document.getElementById('mine-chip');e.click();setTimeout(()=>{const x=()=>new DOMMatrixReadOnly(getComputedStyle(e,'::before').transform).m41;const a=x();e.click();window.reverseJump=Math.abs(a-x());},60)})()")
        rows=finish(page,'reverse');m=metrics(rows)
        ck('中途反向没有位置跳变',page.evaluate('reverseJump')<.001 and abs(m['last'])<.02,m)
        rev_index=next(i for i,r in enumerate(rows[1:],1) if rows[i-1]['checked'] and not r['checked'])
        ck('反向保留惯性后收敛',rows[rev_index]['x']>=rows[rev_index-1]['x'] and stable(rows))
        page.evaluate(CAPTURE)
        page.evaluate("(()=>{const e=document.getElementById('mine-chip');for(let i=0;i<9;i++)setTimeout(()=>e.click(),i*35)})()")
        rows=finish(page,'rapid');m=metrics(rows)
        ck('九次连点最后 checked 与终点一致',page.locator('#mine-chip').is_checked() and m['last']==14 and stable(rows),m)
        ck('连点不重复业务事件',page.evaluate('changeCount')==14,page.evaluate('changeCount'))
        page.evaluate("(()=>{const e=document.getElementById('mine-chip'),c=BAND.switch(e);window.savedRevision=c.revision;e.click();window.staleAccepted=c.setChecked(true,{revision:savedRevision});})()")
        page.wait_for_timeout(1400)
        ck('旧 revision 回显不覆盖新选择',page.evaluate('!staleAccepted') and not page.locator('#mine-chip').is_checked())
        page.evaluate("(()=>{const e=document.getElementById('mine-chip');BAND.switch(e).setChecked(true);window.beforeQuiet=changeCount;})()")
        page.evaluate(CAPTURE,dict(ms=350,arm=False));rows=finish(page,'external-restore')
        ck('真实外部恢复立即到端点不造 change',all(r['x']==14 for r in rows) and page.evaluate('changeCount===beforeQuiet'))
        page.evaluate("document.getElementById('mine-chip').disabled=true")
        before=page.evaluate('changeCount')
        page.evaluate("document.getElementById('mine-chip').click()")
        ck('禁用无动作',page.locator('#mine-chip').is_checked() and page.evaluate('changeCount')==before)
        page.evaluate("const e=document.getElementById('mine-chip');e.disabled=false;e.readOnly=true")
        page.locator('label:has(#mine-chip) span').click()
        page.locator('#mine-chip').focus();page.keyboard.press('Space')
        ck('只读标签和空格均无动作',page.locator('#mine-chip').is_checked() and page.evaluate('changeCount')==before)
        page.evaluate("document.getElementById('mine-chip').readOnly=false;BAND.switch(document.getElementById('mine-chip')).setChecked(false)")
        page.locator('label:has(#mine-chip) span').click();page.wait_for_timeout(70)
        page.emulate_media(reduced_motion='reduce');page.wait_for_timeout(100)
        page.evaluate(CAPTURE,dict(ms=200,arm=False));rows=finish(page,'reduce-midflight')
        ck('运动中减少动态立即终态且清残留',all(r['x']==14 for r in rows) and page.locator('#mine-chip').evaluate("e=>!e.style.getPropertyValue('--band-switch-x')"))
        page.keyboard.press('Space');page.evaluate(CAPTURE,dict(ms=200,arm=False));rows=finish(page,'reduce-off')
        ck('减少动态操作无运动',all(r['x']==0 for r in rows))
        page.emulate_media(reduced_motion='no-preference')
        # 动态挂载与双开关各自独立，均使用生产共用实现。
        page.evaluate("document.querySelector('.mbody').insertAdjacentHTML('afterbegin','<form id=probe-form><label class=band-switch><input id=second type=checkbox role=switch checked><span>隔离二值设置</span></label></form>')")
        page.wait_for_timeout(50)
        page.evaluate(CAPTURE,dict(selector='#second',ms=200,arm=False));rows=finish(page,'dynamic-checked')
        ck('动态已选控件首帧直接端点',all(r['x']==14 for r in rows))
        page.evaluate("window.secondChanges=0;document.getElementById('second').addEventListener('change',()=>secondChanges++);document.getElementById('second').click();document.getElementById('mine-chip').click();")
        page.wait_for_timeout(90)
        page.evaluate("document.getElementById('probe-form').reset()")
        page.evaluate(CAPTURE,dict(ms=1400,arm=False));rows=finish(page,'independent-reset')
        ck('表单还原只复位所属开关',page.locator('#second').is_checked() and metrics(rows)['max']>15 and metrics(rows)['last']==14)
        page.evaluate("window.firstAPI=BAND.switch(document.getElementById('second'));window.sameAPI=firstAPI===BAND.switch(document.getElementById('second'));document.getElementById('second').click()")
        ck('重复挂载不增加监听',page.evaluate('sameAPI && secondChanges===2'))
        page.evaluate("document.getElementById('probe-form').remove()")
        page.wait_for_timeout(50)
        ck('移除控件清理控制器',page.evaluate('firstAPI.setChecked(true)===false'))
        # 真实列表翻页 / 状态叠加 / 代际响应。
        ready();page.locator('#next').click();page.wait_for_timeout(120)
        page.locator('[data-s="FIXED"]').check();page.wait_for_timeout(120)
        page.locator('#mine-chip').check();page.wait_for_timeout(120)
        ck('筛选原查询语义与页码重置',requests[-1].get('status')==['FIXED'] and requests[-1].get('mine')==['1'] and requests[-1].get('page')==['1'])
        hold_next=True;page.locator('#mine-chip').uncheck();page.wait_for_timeout(100)
        page.locator('#mine-chip').check();page.wait_for_timeout(100)
        for route in held: respond(route,dict(list=[],total=999))
        held.clear();page.wait_for_timeout(150)
        ck('晚到旧列表不改 checked 或计数',page.locator('#mine-chip').is_checked() and page.locator('#count').inner_text()=='共 60 条')
        # 六种旧配色的日夜与三宽度，检查几何、焦点和运动。
        for width in (1280,390,320):
            page.bring_to_front()
            page.set_viewport_size(dict(width=width,height=900))
            for palette in ('jade','teal','violet','olive','clay','ink'):
                for theme in ('light','dark'):
                    page.evaluate("([p,t])=>{document.documentElement.dataset.palette=p;document.documentElement.dataset.theme=t;}",[palette,theme])
                    page.evaluate("BAND.switch(document.getElementById('mine-chip')).setChecked(false)")
                    page.evaluate(CAPTURE,dict(ms=1200));page.locator('#mine-chip').click()
                    rows=finish(page,f'{width}-{palette}-{theme}')
                    ck(f'{width}/{palette}/{theme} 运动及触区',metrics(rows)['max']>15 and stable(rows) and page.locator('label:has(#mine-chip)').bounding_box()['height']>=44)
                    ratios=read_contrast(page)
                    ck(f'{width}/{palette}/{theme} 可辨识',ratios['text']>=4.5 and ratios['edge']>=3 and ratios['thumb']>=3,ratios)
            page.screenshot(path=str(OUT/f'issues-{width}.png'),full_page=True)
        # 实际主题页生成三档令牌，在原页面重载后复验共用开关。
        theme_page=ctx.new_page();theme_page.on('pageerror',lambda e:errors.append(str(e)))
        for contrast in ('standard','medium','high'):
            theme_page.bring_to_front()
            theme_page.goto(base+'/theme.html');theme_page.locator('#theme-hex').fill('#963c84');theme_page.locator('#theme-hex').blur()
            theme_page.locator(f'input[name="contrast"][value="{contrast}"]').locator('..').click()
            theme_page.locator('#theme-apply').click();theme_page.wait_for_timeout(100)
            ready()
            for dark in (False,True):
                page.evaluate('(dark)=>BAND.appearance.setTheme(dark?"dark":"light")',dark)
                page.evaluate(CAPTURE,dict(ms=1200));page.locator('#mine-chip').click()
                rows=finish(page,f'new-{contrast}-{dark}')
                ck(f'新主题 {contrast}/{dark} 共用运动',stable(rows) and (metrics(rows)['max']>15 if page.locator('#mine-chip').is_checked() else metrics(rows)['min']<-1))
                ratios=read_contrast(page)
                ck(f'新主题 {contrast}/{dark} 可辨识',ratios['text']>=4.5 and ratios['edge']>=3 and ratios['thumb']>=3,ratios)
        theme_page.close()
        # 共用能力不依赖 Storage；这里是只加载真实 CSS / JS 的隔离宿主页。
        no_storage=browser.new_context(viewport=dict(width=390,height=900),service_workers='block')
        no_storage.add_init_script("for(const k of ['getItem','setItem','removeItem'])Storage.prototype[k]=()=>{throw new DOMException('isolated denial','SecurityError')}")
        no_storage.route('**/switch-fixture-251.html',lambda r:r.fulfill(content_type='text/html',body='''<!doctype html><meta charset=utf-8><link rel=stylesheet href=/band.css><script src=/band-core.js></script><label class=band-switch><input id=wide type=checkbox role=switch style="--band-switch-width:52px;--band-switch-height:30px;--band-switch-thumb:20px;--band-switch-inset:5px;--band-switch-travel:20px"><span>隔离宽开关</span></label><label class=band-choice><input id=plain type=checkbox><span>普通多选</span></label>'''))
        isolated=no_storage.new_page();isolated.on('pageerror',lambda e:errors.append(str(e)))
        isolated.goto(base+'/switch-fixture-251.html');isolated.bring_to_front()
        isolated.wait_for_function("typeof BAND!=='undefined' && !!BAND.switch")
        isolated.evaluate(CAPTURE,dict(selector='#wide'));isolated.get_by_role('switch',name='隔离宽开关').click()
        rows=finish(isolated,'storage-denied-wide-on');m=metrics(rows)
        ck('无 Storage 的宽轨开关仍双向可用',m['max']>23 and abs(m['last']-20)<.02 and stable(rows),m)
        isolated.evaluate(CAPTURE,dict(selector='#wide'));isolated.get_by_role('switch',name='隔离宽开关').click()
        rows=finish(isolated,'storage-denied-wide-off');m=metrics(rows)
        ck('20px 行程关闭可见回弹',m['min']<-3 and abs(m['last'])<.02 and stable(rows),m)
        ck('普通多选不被共用扫描接管',isolated.locator('#plain').evaluate('e=>BAND.switch(e)===null') and isolated.get_by_role('checkbox',name='普通多选').count()==1)
        no_storage.close()
        ck('无新增页面脚本错误',not errors,errors)
        browser.close()
    server.shutdown()


if __name__=='__main__':
    try: run()
    finally:
        (OUT/'results.json').write_text(json.dumps(dict(checks=checks,errors=errors),ensure_ascii=False,indent=2),encoding='utf-8')
        (OUT/'frames.json').write_text(json.dumps(traces,ensure_ascii=False),encoding='utf-8')
    raise SystemExit(0 if checks and all(c['passed'] for c in checks) and not errors else 1)
