"""#247/#248 探针的隔离接口夹具；不读写真实业务资料。"""
import sys,json,threading,time,re
from pathlib import Path
from urllib.parse import urlsplit,parse_qs
from http.server import SimpleHTTPRequestHandler,ThreadingHTTPServer
HERE=Path(__file__).resolve().parent.parent/'design-samples'
sys.path.insert(0,str(HERE/'.deps-246'))
from playwright.sync_api import sync_playwright
sys.stdout.reconfigure(encoding='utf-8')
STATIC=HERE.parent/'src/main/resources/static'
checks=[];requests=[];errors=[];pending={};hold=set();fail=set();role='ADMIN'
profile={'id':1,'name':'验证甲','avatar':None,'intro':'隔离资料','contact':'demo','birthday':'2003-05-18','joinedAt':'2023-09-01','sectionName':'二胡','instrument':'二胡','role':'ADMIN','status':'ACTIVE','account':'fixture'}
piece={'id':1,'title':'验证曲','status':'CANDIDATE','statusLabel':'备选'}
member={**profile,'sectionId':1,'activated':True}
def respond(route,data=None,code=0):
    route.fulfill(status=200,content_type='application/json',body=json.dumps({'code':code,'message':'模拟错误' if code else 'ok','data':data},ensure_ascii=False))
def api(route):
    global profile
    req=route.request;u=urlsplit(req.url);path=u.path[5:];method=req.method
    body=req.post_data_json if method in ['PUT','POST'] and 'json' in req.headers.get('content-type','') else None
    requests.append({'path':path,'query':u.query,'method':method,'body':body})
    key=method+' '+path
    if key in hold:pending.setdefault(key,[]).append(route);return
    if key in fail:respond(route,None,10003);return
    data={}
    if path=='auth/me':data={**member,'role':role}
    elif path=='avatars':data=json.loads((STATIC/'vendor/avatars/manifest.json').read_text(encoding='utf-8'))['avatars']
    elif path in ['birthdays/today','me/recent-activity','recordings/assignments/current']:data=[]
    elif path in ['release-notes/latest','me/section-note-pending']:data=None
    elif path=='issues/activity':data={'latestUpdatedAt':'2026-11-01T00:00:00'}
    elif path=='sections':data=[{'id':1,'name':'二胡'}]
    elif path=='me/profile':
        if method=='PUT':
            profile={**profile,**{k:v for k,v in body.items() if k not in ['clearBirthday','clearAvatarFrame']}}
            if body.get('clearBirthday'):profile['birthday']=None
            if body.get('clearAvatarFrame'):profile['avatarFrame']=None
        data={**profile,'role':role}
    elif path.endswith('showcase-count'):data={'count':0}
    elif path.startswith('members/') and ('showcase' in path or 'concert' in path):data={'count':0}
    elif path=='members':data={'list':[member],'total':1}
    elif path=='members/1':data=member
    elif path=='me/profile-visits':data={'list':[],'total':0} if method=='GET' else None
    elif path=='pieces/1':data={'piece':piece,'attachments':[]}
    elif path=='pieces':data={'list':[piece],'total':1}
    elif path=='concerts/1':data={'concert':{'id':1,'name':'验证演出','date':'2026-11-04','status':'PLANNED','statusLabel':'计划中'},'program':[],'cast':[],'photos':[]}
    elif path=='issues':data={'list':[],'total':0} if method=='GET' else {'id':2}
    elif path=='files':data={'id':9,'originalName':'fixture.wav','sizeBytes':48,'contentType':'audio/wav'}
    elif path.startswith('recordings/manage'):data={'weekStart':'2026-11-02','sections':[],'members':[]}
    elif path.startswith('events') or path.startswith('recordings') or path.startswith('concerts'):data={'list':[],'total':0}
    respond(route,data)
class Handler(SimpleHTTPRequestHandler):
    def __init__(self,*a,**kw):super().__init__(*a,directory=str(STATIC),**kw)
    def log_message(self,*a):pass
def metrics(page,selector):
    return page.locator(selector).evaluate('''b=>{const r=b.getBoundingClientRect(),icon=b.querySelector('.bd-icon'),label=b.querySelector('.bd-label'),range=document.createRange();range.selectNodeContents(label);const t=range.getBoundingClientRect(),i=icon.getBoundingClientRect();return {phase:b.dataset.bdPhase,visible:getComputedStyle(icon).visibility!=='hidden',width:r.width,height:r.height,x:i.x-r.x,y:i.y-r.y,center:Math.abs((t.left+t.right-r.left-r.right)/2)}}''')