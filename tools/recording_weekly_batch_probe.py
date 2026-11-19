#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""#263 / #264 / #265 **生产静态页探针**（不是样张探针）。

跑在真实 `src/main/resources/static/recordings.html` 上：本地起一个静态服务器托管该目录，
`/api/**` 全部由路由桩接管（不登录、不连数据库、不写真实业务数据）。
只量真实 DOM、真实事件、真实请求序列与最终计算样式。

前置：无需启动 Spring Boot（静态文件直接来自 `src/`）；Playwright 从
`design-samples/.deps-246` 借用（本机既有、已 gitignore）。
跑法：python -X utf8 tools/recording_weekly_batch_probe.py
失败退出码 1。

覆盖（对应 docs/08 §8.4 / docs/14 §14.6.4 的验收表，逐条在 checks 名单里标了编号）：
  R263-01 时区归周 / R263-02 折叠与计数 / R263-03 超过 50 条 / R263-04 加载与竞态
  U264-01 入口 / U264-02 预检 / U264-03 命名隔离 / U264-05 队列 / U264-06 部分失败
  U264-07 不确定结果 / L265 布局、来源切换、唯一提交区、焦点
  A268-01—08 同一批次空闲追加：入口 / 顺序 / 取消与预检 / 草稿与关联 / 执行中锁 /
             多轮与混合状态 / 关闭后迟到选择 / 三宽入口
未覆盖（需真机 / 真录音，回执里如实列"未验"）：真音频解码与试听、页面内录音与节拍器
"耳朵听到"、Android / iPhone 多次选择与真机文件选择器返回、真机键盘与读屏。
"""
import json
import re
import sys
import threading
from datetime import date, timedelta
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, urlsplit

ROOT = Path(__file__).resolve().parent.parent
STATIC = ROOT / "src" / "main" / "resources" / "static"
sys.path.insert(0, str(ROOT / "design-samples" / ".deps-246"))
from playwright.sync_api import sync_playwright  # noqa: E402

CHECKS = []
ERRORS = []
REQUESTS = []

STATE = {}


def reset_state():
    STATE.clear()
    STATE.update({
        "hold": set(),          # 要扣住的 GET /api/recordings 页码
        "held": [],             # 已扣住的 route（稍后手动放行）
        "fail": set(),          # 返回业务错误的页码
        "plan": [],             # POST /api/recordings 的应答计划：ok | fail | abort
        "file_posts": [],       # POST /api/files 的原始文件名（按顺序）
        "record_bodies": [],    # POST /api/recordings 的 body（按顺序）
        "created": [],          # 成功创建的 fileId（按顺序）
        "extra_rows": [],       # 让 GET 列表额外可见的行（核对"结果待确认"用）
        "hold_posts": 0,        # 让下 N 个 POST /api/files 先挂着不答（量执行中锁定，`#268`）
        "held_posts": [],       # 已扣住的 (route, name)
    })


def respond(route, data=None, code=0):
    route.fulfill(
        status=200,
        content_type="application/json",
        body=json.dumps({"code": code, "message": "模拟错误" if code else "ok", "data": data},
                        ensure_ascii=False),
    )


MEMBER = {
    "id": 1, "name": "验证甲", "avatar": None, "intro": "隔离资料", "contact": "demo",
    "birthday": "2003-05-18", "joinedAt": "2023-09-01", "sectionId": 1, "sectionName": "二胡",
    "instrument": "二胡", "role": "MEMBER", "status": "ACTIVE", "account": "probe", "activated": True,
}


def build_records():
    """120 条、4 个周；W1（55 条）跨第 1/2 页边界，W2（30 条）跨第 2/3 页边界。"""
    weeks = [(date(2026, 11, 16), 8, 8), (date(2026, 11, 9), 55, 8),
             (date(2026, 11, 2), 30, 5), (date(2026, 10, 26), 27, 4)]
    stamps = []
    for monday, n, per_day in weeks:
        for i in range(n):
            d = monday + timedelta(days=i // per_day)
            h = 21 - (i % per_day)
            stamps.append(f"{d.isoformat()}T{h:02d}:{i % 60:02d}:00")
    stamps.sort(reverse=True)
    rows = []
    for idx, ts in enumerate(stamps):
        rid = 1200 - idx
        rows.append({
            "id": rid, "memberId": 1, "fileId": 5000 + idx, "title": f"录音 {rid}",
            "uploadedAt": ts, "durationSec": 42, "sizeBytes": 2048, "commentCount": 0,
            "memberName": "验证甲", "sectionName": "二胡", "pieceTitle": None,
            "linked": False, "assignmentId": None,
        })
    return rows


RECORDS = build_records()


def api(route):
    req = route.request
    u = urlsplit(req.url)
    path = u.path[5:] if u.path.startswith("/api/") else u.path
    q = parse_qs(u.query)
    method = req.method
    REQUESTS.append({"method": method, "path": path, "query": u.query})

    if path == "files" and method == "POST":
        name = "fixture"
        try:
            for part in req.post_data_buffer.split(b'name="file"')[1].split(b"\r\n\r\n")[0].split(b"filename="):
                m = re.search(rb'"([^"]+)"', part)
                if m:
                    name = m.group(1).decode("utf-8", "replace")
                    break
        except Exception:
            pass
        STATE["file_posts"].append(name)
        if STATE["hold_posts"] > 0:
            STATE["hold_posts"] -= 1
            STATE["held_posts"].append((route, name))
            return
        return respond(route, {"id": 7000 + len(STATE["file_posts"]), "originalName": name,
                               "contentType": "audio/mp4", "sizeBytes": 16})

    if path == "recordings" and method == "GET":
        page = int((q.get("page") or ["1"])[0])
        size = int((q.get("size") or ["50"])[0])
        if page in STATE["hold"]:
            STATE["held"].append(route)
            return
        if page in STATE["fail"]:
            return respond(route, None, 10003)
        rows = STATE["extra_rows"] + RECORDS
        return respond(route, {"list": rows[(page - 1) * size: page * size],
                               "total": len(rows)})

    if path == "recordings" and method == "POST":
        try:
            body = req.post_data_json
        except Exception:
            body = {}
        STATE["record_bodies"].append(body)
        plan = STATE["plan"]
        act = plan.pop(0) if plan else "ok"
        if act == "abort":
            route.abort()
            return
        if act == "fail":
            return respond(route, None, 40001)
        STATE["created"].append(body.get("fileId"))
        return respond(route, {"id": 9000 + len(STATE["created"]),
                               "uploadedAt": "2026-11-16T10:30:00", "fileId": body.get("fileId")})

    if re.fullmatch(r"recordings/\d+", path) and method == "DELETE":
        return respond(route, {"ok": True})

    if path == "auth/me":
        return respond(route, MEMBER)
    if path == "recordings/assignments/current":
        return respond(route, [])
    if path == "pieces":
        return respond(route, {"list": [], "total": 0})
    if path == "sections":
        return respond(route, [{"id": 1, "name": "二胡"}])
    if path in ("avatars",):
        return respond(route, [])
    if path in ("birthdays/today", "me/recent-activity"):
        return respond(route, [])
    if path in ("release-notes/latest", "me/section-note-pending"):
        return respond(route, None)
    if path == "issues/activity":
        return respond(route, {"latestUpdatedAt": "2026-11-01T00:00:00"})
    if path == "me/profile":
        return respond(route, MEMBER)
    if path.endswith("showcase-count"):
        return respond(route, {"count": 0})
    if path.startswith("recordings/manage"):
        return respond(route, {"weekStart": "2026-11-16", "sections": [], "members": [],
                               "assignments": [], "weeks": []})
    return respond(route, {})


class Handler(SimpleHTTPRequestHandler):
    def __init__(self, *a, **kw):
        super().__init__(*a, directory=str(STATIC), **kw)

    def log_message(self, *a):
        pass


def ck(name, ok, detail=None):
    CHECKS.append({"name": name, "pass": bool(ok), "detail": detail})
    print(("OK   " if ok else "FAIL ") + name + (f" | {detail}" if detail is not None else ""), flush=True)


def make_context(browser, **kw):
    kw.setdefault("service_workers", "block")
    ctx = browser.new_context(**kw)
    ctx.add_init_script(
        "localStorage.setItem('band_token','probe-token');"
        "sessionStorage.setItem('band.welcome.done','1');")
    ctx.route("**/api/**", api)
    return ctx


def open_page(ctx, base, expect_weeks=True):
    page = ctx.new_page()
    page.on("pageerror", lambda e: ERRORS.append(str(e)))
    page.goto(base + "/recordings.html")
    page.wait_for_function("typeof BAND!=='undefined' && !!BAND.me", timeout=20000)
    if expect_weeks:
        page.wait_for_selector("#list .rc-myweek", timeout=20000)
    return page


def release_held_posts():
    held = STATE["held_posts"][:]
    STATE["held_posts"].clear()
    for route, name in held:
        respond(route, {"id": 7000 + len(STATE["file_posts"]), "originalName": name,
                        "contentType": "audio/mp4", "sizeBytes": 16})


def release_held():
    held = STATE["held"][:]
    STATE["held"].clear()
    STATE["hold"].clear()
    for route in held:
        u = urlsplit(route.request.url)
        q = parse_qs(u.query)
        page = int((q.get("page") or ["1"])[0])
        size = int((q.get("size") or ["50"])[0])
        rows = STATE["extra_rows"] + RECORDS
        respond(route, {"list": rows[(page - 1) * size: page * size], "total": len(rows)})


def weeks_snapshot(page):
    return page.eval_on_selector_all(
        ".rc-myweek",
        """els => els.map(e => ({
             key: e.dataset.week,
             open: !e.querySelector('.rc-myweek-b').hidden,
             label: e.querySelector('.rc-myweek-t').textContent,
             countText: e.querySelector('.rc-myweek-n').textContent,
             cards: e.querySelectorAll('.rc-myweek-b .rc-rec').length
           }))""")


def file_input(page):
    return page.locator("#u-file")


def select_files(page, specs):
    files = [{"name": n, "mimeType": m, "buffer": b"RIFF" + bytes(12 + i)}
             for i, (n, m) in enumerate(specs)]
    file_input(page).set_input_files(files)


def append_files(page, specs, cancel=False):
    """#268 追加必须**真实走一遍点击 → 文件选择器 → change**：
    只 set_input_files 会跳过 click，拿不到「点开时记下的批次」，测的就不是生产路径。"""
    files = []
    for s in specs:
        name, mime = s[0], s[1]
        buf = s[2] if len(s) > 2 else b"RIFF" + bytes(8)
        files.append({"name": name, "mimeType": mime, "buffer": buf})
    with page.expect_file_chooser() as fc:
        page.locator("#ws-add").click()
    fc.value.set_files([] if cancel else files)


def run():
    reset_state()
    server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    base = f"http://127.0.0.1:{server.server_port}"
    chrome = r"C:\Program Files\Google\Chrome\Application\chrome.exe"

    with sync_playwright() as pw:
        browser = pw.chromium.launch(executable_path=chrome, headless=True, args=[
            "--disable-background-timer-throttling", "--disable-renderer-backgrounding",
            "--disable-backgrounding-occluded-windows"])

        # ═════════════ R263：归周 / 计数 / 加载更早 ═════════════
        reset_state()
        ctx = make_context(browser, viewport={"width": 1280, "height": 900})
        page = open_page(ctx, base)
        page.wait_for_timeout(200)
        weeks = weeks_snapshot(page)
        ck("R263-02 首屏只渲染有已加载录音的周（2 个）", len(weeks) == 2, weeks)
        ck("R263-02 周按周一倒序", [w["key"] for w in weeks] == sorted([w["key"] for w in weeks], reverse=True),
           [w["key"] for w in weeks])
        ck("R263-02 最近非空周展开、旧周折叠",
           weeks[0]["open"] and all(not w["open"] for w in weeks[1:]))
        ck("R263-02 周标题是实际日期范围", weeks[0]["label"] == "2026.11.16–11.22",
           [w["label"] for w in weeks])
        ck("R263-02/03 页头显示已加载 / 共",
           page.locator("#mine-count").inner_text().strip() == "已加载 50 / 共 120 条",
           page.locator("#mine-count").inner_text())
        ck("R263-02 各周计数与卡数一致",
           all(w["cards"] == int(re.search(r"\d+", w["countText"]).group()) for w in weeks))

        page.locator("#mine-more").click()
        page.wait_for_function("document.querySelectorAll('#list .rc-rec').length === 100")
        page.locator("#mine-more").click()
        page.wait_for_function("document.querySelectorAll('#list .rc-rec').length === 120")
        page.wait_for_timeout(200)
        ids = page.eval_on_selector_all("#list .rc-rec", "els => els.map(e => e.dataset.id)")
        ck("R263-03 120 条全部可达且 id 不重复", len(ids) == 120 and len(set(ids)) == 120, len(ids))
        weeks = weeks_snapshot(page)
        ck("R263-03 加载完后 4 个周标题、跨页同周只有一个标题",
           len(weeks) == 4
           and len([w for w in weeks if w["key"] == "2026-11-09"]) == 1
           and [w for w in weeks if w["key"] == "2026-11-09"][0]["cards"] == 55, weeks)
        ck("R263-02 跨年 / 非整月日期范围可辨识",
           [w for w in weeks if w["key"] == "2026-10-26"][0]["label"] == "2026.10.26–11.1",
           [w["label"] for w in weeks])
        ck("R263-03 加载完后按钮撤掉", page.locator("#mine-more").is_hidden())
        ck("R263-03 显示「已加载全部」",
           page.locator(".rc-mine-done").count() == 1 and page.locator(".rc-mine-done").is_visible(),
           page.locator(".rc-mine-done").all_inner_texts())
        ck("R263-02 计数与 total 对得上",
           page.locator("#mine-count").inner_text().strip() == "已加载 120 / 共 120 条",
           page.locator("#mine-count").inner_text())
        ctx.close()

        # ═════════════ R263-01：浏览器时区不改变归周 ═════════════
        reset_state()
        ctx = make_context(browser, viewport={"width": 1280, "height": 900},
                           timezone_id="America/Los_Angeles")
        page = open_page(ctx, base)
        page.wait_for_timeout(150)
        la_weeks = [w["key"] for w in weeks_snapshot(page)]
        ck("R263-01 浏览器在美洲时区仍按上海日期归周",
           la_weeks == ["2026-11-16", "2026-11-09"], la_weeks)
        ctx.close()

        # ═════════════ R263-04：失败原位重试 ═════════════
        reset_state()
        STATE["fail"] = {2}
        ctx = make_context(browser, viewport={"width": 1280, "height": 900})
        page = open_page(ctx, base)
        page.locator("#mine-more").click()
        page.wait_for_selector(".rc-mine-err", timeout=10000)
        ck("R263-04 失败保留已加载内容、按钮变重试",
           page.locator("#list .rc-rec").count() == 50
           and page.locator("#mine-more").inner_text().strip() == "重试",
           page.locator("#mine-more").inner_text())
        STATE["fail"].clear()
        page.locator("#mine-more").click()
        page.wait_for_function("document.querySelectorAll('#list .rc-rec').length === 100", timeout=10000)
        ck("R263-04 原位重试后追加成功", True)
        ctx.close()

        # ═════════════ R263-04：加载中被 reset（上传 / 删除 / 刷新）不能卡死 ═════════════
        reset_state()
        STATE["hold"] = {2}
        ctx = make_context(browser, viewport={"width": 1280, "height": 900})
        page = open_page(ctx, base)
        page.locator("#mine-more").click()
        page.wait_for_function("window.__nada === undefined || true")
        for _ in range(100):
            if STATE["held"]:
                break
            page.wait_for_timeout(50)
        ck("R263-04 夹具扣住了第 2 页请求", bool(STATE["held"]))
        page.evaluate("loadMine({reset: true})")     # 模拟上传 / 删除后的重置
        page.wait_for_timeout(200)
        release_held()                                # 旧响应晚到，必须被丢弃
        page.wait_for_timeout(400)
        count = page.locator("#list .rc-rec").count()
        ck("R263-04 加载中 reset 仍从第一页重新取到列表", count == 50, count)
        ck("R263-04 加载中 reset 不把按钮卡在「正在加载…」",
           not page.locator("#mine-more").is_disabled()
           and "正在加载" not in page.locator("#mine-more").inner_text(),
           page.locator("#mine-more").inner_text())
        ctx.close()

        # ═════════════ U264 / L265：入口、预检、队列、草稿隔离、布局 ═════════════
        reset_state()
        ctx = make_context(browser, viewport={"width": 1280, "height": 900})
        page = open_page(ctx, base, expect_weeks=False)
        page.wait_for_timeout(300)
        page.locator("#go-record").click()          # 录音台是 body[data-rcscreen=detail] 那一屏
        page.wait_for_timeout(200)
        ck("L265 首屏直接显示两个来源入口",
           page.locator("#ws-sources").is_visible()
           and page.locator("#pick-record").is_visible()
           and page.locator("label[for=u-file]").is_visible())
        ck("L265 首屏不预填文件、不显示命名表单与提交区",
           page.locator("#ws-rec").is_hidden() and page.locator("#ws-files").is_hidden()
           and page.locator("#ws-ops").is_hidden())
        ck("U264-01 文件 input 允许 multiple 且 accept 无通配 / capture",
           page.get_attribute("#u-file", "multiple") is not None
           and "audio/*" not in (page.get_attribute("#u-file", "accept") or "")
           and page.get_attribute("#u-file", "capture") is None,
           page.get_attribute("#u-file", "accept"))

        select_files(page, [("甲.m4a", "audio/mp4"), ("乙.m4a", "audio/mp4"), ("坏.txt", "text/plain")])
        page.wait_for_timeout(300)
        ck("U264-02 合格项入队、拒绝项单独列出",
           page.locator(".rc-qi").count() == 2
           and page.locator("#ws-rejects").is_visible()
           and "坏.txt" in page.locator("#ws-rejects").inner_text(),
           page.locator("#ws-rejects").inner_text())
        ck("U264-01 选完零上传（没有文件 / 记录请求）",
           not STATE["file_posts"] and not STATE["record_bodies"], REQUESTS[-4:])
        vis_primary = page.locator("#upload-box button.rc-btn-primary:visible").count()
        ck("U264-05 多文件时唯一提交入口叫「上传 2 条」",
           page.locator("#rv-up").inner_text().strip() == "上传 2 条" and vis_primary == 1,
           {"label": page.locator("#rv-up").inner_text(), "visiblePrimary": vis_primary})
        ck("L265 文件分支有 260ms 入场动效（非 reduced）",
           page.eval_on_selector("#ws-files", "e => getComputedStyle(e).animationName") == "rcWsIn",
           page.eval_on_selector("#ws-files", "e => getComputedStyle(e).animationName"))
        ck("L265 提交区在普通文档流、不是 fixed / sticky",
           page.eval_on_selector("#ws-ops", "e => getComputedStyle(e).position") == "static")

        # 切项：草稿隔离 + 焦点保留
        rows = page.locator(".rc-qi-main")
        rows.nth(0).click()
        page.fill("#u-title", "甲手写")
        rows.nth(1).click()
        page.wait_for_timeout(150)
        b_title = page.input_value("#u-title")
        active_qid = page.evaluate("document.activeElement && document.activeElement.dataset.qid")
        rows.nth(0).click()
        page.wait_for_timeout(150)
        a_title = page.input_value("#u-title")
        ck("U264-03 每项标题独立（A 手写不串到 B）", b_title != "甲手写", b_title)
        ck("U264-03 切回 A 仍保留 A 的手写", a_title == "甲手写", a_title)
        ck("L265 切项后焦点仍在该清单项", active_qid is not None, active_qid)

        # 布局：桌面两列 / 窄屏堆叠
        cols = page.eval_on_selector("#upload-box .rc-ws-split",
                                     "e => getComputedStyle(e).gridTemplateColumns")
        ck("L265 >650px 清单 + 编辑两列", len(cols.split()) == 2, cols)
        page.set_viewport_size({"width": 390, "height": 844})
        page.wait_for_timeout(300)
        narrow = page.eval_on_selector("#upload-box", "e => e.classList.contains('narrow')")
        cols2 = page.eval_on_selector("#upload-box .rc-ws-split",
                                      "e => getComputedStyle(e).gridTemplateColumns")
        listbox = page.locator(".rc-ws-listcol").bounding_box()
        editbox = page.locator("#ws-edit").bounding_box()
        ck("L265 ≤650px 清单在上、编辑在下",
           narrow and len(cols2.split()) == 1 and listbox and editbox and listbox["y"] < editbox["y"],
           {"narrow": narrow, "cols": cols2})
        ck("L265 本机分支不展示节拍器", page.locator("#metro-card").is_hidden())

        # 更换添加方式：有未完成内容时不能丢草稿
        page.locator("#ws-change").click()
        page.wait_for_timeout(150)
        toasts = page.locator("#toast-host").inner_text()
        ck("L265 有未上传内容时「更换添加方式」被挡住",
           page.locator("#ws-files").is_visible() and "先上传或丢弃" in toasts, toasts)
        ctx.close()

        # ═════════════ A268：同一批次空闲追加（docs/08 §8.4） ═════════════
        reset_state()
        ctx = make_context(browser, viewport={"width": 1280, "height": 900})
        page = open_page(ctx, base, expect_weeks=False)
        page.wait_for_timeout(300)
        page.locator("#go-record").click()
        page.wait_for_timeout(200)
        select_files(page, [("A.m4a", "audio/mp4"), ("B.m4a", "audio/mp4")])
        page.wait_for_timeout(250)
        page.locator(".rc-qi-main").nth(0).click()
        page.fill("#u-title", "甲手写")
        active_before = page.evaluate(
            "document.querySelector('.rc-qi-main[aria-pressed=\"true\"]').dataset.qid")
        ck("A268-01 清单旁有「继续添加文件」原生入口（multiple / 无通配 / 无 capture）",
           page.locator("#ws-add").is_visible()
           and page.get_attribute("#u-file-more", "multiple") is not None
           and "audio/*" not in (page.get_attribute("#u-file-more", "accept") or "")
           and page.get_attribute("#u-file-more", "capture") is None,
           page.get_attribute("#u-file-more", "accept"))
        for width in (390, 320):
            page.set_viewport_size({"width": width, "height": 900})
            page.wait_for_timeout(250)
            box = page.locator("#ws-add").bounding_box()
            layout = page.evaluate(
                "() => ({ scroll: document.documentElement.scrollWidth, view: innerWidth })")
            ck("A268-08 %dpx 追加入口可见、触区 ≥44px、不横溢" % width,
               box is not None and box["height"] >= 44
               and layout["scroll"] <= layout["view"] + 1, {"box": box, "layout": layout})
        page.set_viewport_size({"width": 1280, "height": 900})
        page.wait_for_timeout(200)

        append_files(page, [
            ("C.m4a", "audio/mp4"),
            ("D.MP3", "audio/mpeg"),
            ("超大.m4a", "audio/mp4", b"RIFF" + b"\0" * (20 * 1024 * 1024 + 1)),
        ])
        page.wait_for_timeout(300)
        rows = page.locator(".rc-qi-main")
        qids = page.eval_on_selector_all(".rc-qi-main", "els => els.map(e => e.dataset.qid)")
        ck("A268-01 追加按 FileList 顺序接末尾、qid 继续递增不冲突",
           rows.count() == 4 and len(set(qids)) == 4, qids)
        ck("A268-02 混选保留合格项、拒绝项列本次原名",
           page.locator("#ws-rejects").is_visible()
           and "超大.m4a" in page.locator("#ws-rejects").inner_text()
           and "D.MP3" not in page.locator("#ws-rejects").inner_text(),
           page.locator("#ws-rejects").inner_text())
        ck("A268-03 旧项手写标题保留、当前编辑项与焦点不跳",
           page.input_value("#u-title") == "甲手写"
           and page.evaluate(
               "document.querySelector('.rc-qi-main[aria-pressed=\"true\"]').dataset.qid") == active_before)

        rows.nth(3).click()   # 最后一条是新追加的 D.MP3
        page.wait_for_timeout(150)
        d_title = page.input_value("#u-title")
        ck("A268-03 新项用自己的文件基名、不继承 A 的手写", d_title != "甲手写" and "D" in d_title, d_title)

        append_files(page, [("C.m4a", "audio/mp4")])
        page.wait_for_timeout(250)
        ck("A268-02 同名文件可并存、不覆盖", page.locator(".rc-qi").count() == 5,
           page.locator(".rc-qi").count())

        before = page.locator(".rc-qi").count()
        append_files(page, [], cancel=True)
        page.wait_for_timeout(150)
        ck("A268-02 取消选择不动旧队列", page.locator(".rc-qi").count() == before)
        append_files(page, [("坏.txt", "text/plain")])
        page.wait_for_timeout(250)
        ck("A268-02 全不合格原清单不变、拒绝项仍列出",
           page.locator(".rc-qi").count() == before
           and "坏.txt" in page.locator("#ws-rejects").inner_text(),
           page.locator("#ws-rejects").inner_text())
        seeds = page.evaluate("batch.items.map(i => i.d.assignId)")
        ck("A268-03 新项关联只取批次共同初值（本夹具为空）", all(s == "" for s in seeds), seeds)

        # 执行中：追加 input 禁用 + 可见说明；放开后恢复，且旧成功勾保留
        STATE["hold_posts"] = 1
        page.locator("#rv-up").click()
        page.wait_for_function("document.getElementById('u-file-more').disabled === true", timeout=10000)
        ck("A268-07 执行中「继续添加文件」禁用并给出可见说明",
           page.locator("#u-file-more").is_disabled()
           and page.locator("#ws-add").get_attribute("aria-disabled") == "true"
           and page.locator("#ws-add-note").is_visible(),
           {"disabled": page.locator("#u-file-more").is_disabled(),
            "note": page.locator("#ws-add-note").inner_text()})
        release_held_posts()
        page.wait_for_function(
            "document.querySelectorAll('.rc-qi-st.done').length === 5", timeout=30000)
        ck("A268-06 全成功后「继续添加文件」恢复可用、说明收起",
           page.locator("#u-file-more").is_enabled()
           and page.locator("#ws-add-note").is_hidden()
           and page.locator("#rv-drop").inner_text().strip() == "关闭清单",
           page.locator("#rv-drop").inner_text())

        # 关闭后迟到的选择不得复活旧批：先真的点开选择器（记下 appendFor = 旧批次），
        # 再关清单，最后才让选择器「返回」文件 —— 模拟原生对话框比 JS 慢的真实时序。
        with page.expect_file_chooser() as fc:
            page.locator("#ws-add").click()
        page.locator("#rv-drop").click()
        page.locator("#ws-sources").wait_for(state="visible", timeout=10000)
        fc.value.set_files([{"name": "迟到.m4a", "mimeType": "audio/mp4", "buffer": b"RIFF" + b"l"}])
        page.wait_for_timeout(200)
        ck("A268-07 关闭后迟到的 FileList 不复活旧批",
           page.evaluate("batch === null") and page.locator("#ws-sources").is_visible(),
           page.evaluate("batch && batch.items.length"))
        ctx.close()

        # 混合状态：失败 + 新追加 → 「上传并重试 K 条」且写明新增 / 重试数
        reset_state()
        STATE["plan"] = ["fail"]
        ctx = make_context(browser, viewport={"width": 1280, "height": 900})
        page = open_page(ctx, base, expect_weeks=False)
        page.wait_for_timeout(300)
        page.locator("#go-record").click()
        page.wait_for_timeout(200)
        select_files(page, [("失败.m4a", "audio/mp4")])
        page.wait_for_timeout(200)
        page.locator("#rv-up").click()
        page.wait_for_function(
            "document.getElementById('rv-up').textContent.trim() === '重试失败 1 条'", timeout=20000)
        failed_id = page.evaluate("batch.items[0].fileId")
        append_files(page, [("新增.m4a", "audio/mp4")])
        page.wait_for_timeout(250)
        label = page.locator("#rv-up").inner_text().strip()
        msg = page.locator("#ws-msg").inner_text()
        ck("A268-05 混合状态按钮「上传并重试 2 条」并写清新增 / 重试数",
           label == "上传并重试 2 条" and "新增 1 条" in msg and "失败重试 1 条" in msg,
           {"label": label, "msg": msg})
        ck("A268-05 追加不动旧失败项的已知 fileId",
           page.evaluate("batch.items[0].fileId") == failed_id, failed_id)
        page.locator("#rv-up").click()
        page.wait_for_function(
            "document.querySelectorAll('.rc-qi-st.done').length === 2", timeout=20000)
        ck("A268-05 统一提交后失败项只重试记录、新项才传文件",
           len(STATE["file_posts"]) == 2 and len(STATE["record_bodies"]) == 3,
           {"files": len(STATE["file_posts"]), "records": len(STATE["record_bodies"])})
        ctx.close()

        # ═════════════ U264-05/06：顺序上传、部分失败、只重试失败 ═════════════
        reset_state()
        ctx = make_context(browser, viewport={"width": 1280, "height": 900})
        page = open_page(ctx, base, expect_weeks=False)
        page.wait_for_timeout(300)
        page.locator("#go-record").click()
        page.wait_for_timeout(200)
        select_files(page, [("甲.m4a", "audio/mp4"), ("乙.m4a", "audio/mp4")])
        page.wait_for_timeout(250)
        page.locator("#rv-up").click()
        page.wait_for_function(
            "document.querySelectorAll('.rc-qi-st.done').length === 2", timeout=20000)
        seq = [(r["method"], r["path"]) for r in REQUESTS
               if (r["method"], r["path"]) in [("POST", "files"), ("POST", "recordings")]][-4:]
        ck("U264-05 逐项两阶段：传文件→存记录，并发 1",
           seq == [("POST", "files"), ("POST", "recordings"),
                   ("POST", "files"), ("POST", "recordings")], seq)
        # #268：全成功后清单保留（旧勾 + 可继续添加），由「关闭清单」显式结束
        ck("A268-06 全成功后清单保留、旧成功勾在、可继续添加",
           page.locator("#ws-files").is_visible() and page.locator("#ws-add").is_visible()
           and page.locator(".rc-qi-st.done").count() == 2
           and page.locator("#rv-drop").inner_text().strip() == "关闭清单",
           {"add": page.locator("#ws-add").is_visible(),
            "drop": page.locator("#rv-drop").inner_text()})
        page.locator("#rv-drop").click()
        page.locator("#ws-sources").wait_for(state="visible", timeout=20000)
        ck("A268-06 关闭清单后回到来源入口", page.locator("#ws-sources").is_visible())
        ctx.close()

        reset_state()
        STATE["plan"] = ["ok", "fail", "ok"]
        ctx = make_context(browser, viewport={"width": 1280, "height": 900})
        page = open_page(ctx, base, expect_weeks=False)
        page.wait_for_timeout(300)
        page.locator("#go-record").click()
        page.wait_for_timeout(200)
        select_files(page, [("一.m4a", "audio/mp4"), ("二.m4a", "audio/mp4"), ("三.m4a", "audio/mp4")])
        page.wait_for_timeout(200)
        page.locator("#rv-up").click()
        # 失败后按钮先亮「上传失败」，约 1.2s 才回到动作名；等它回到「重试失败 1 条」再断言整批结果
        page.wait_for_function(
            "document.getElementById('rv-up').textContent.trim() === '重试失败 1 条'", timeout=20000)
        msg = page.locator("#ws-msg").inner_text()
        statuses = page.eval_on_selector_all(".rc-qi-st", "els => els.map(e => e.textContent.trim())")
        ck("U264-06 中间失败其余继续、计数真实",
           "已上传 2 / 3" in msg and "失败 1" in msg, {"msg": msg, "statuses": statuses})
        ck("U264-06 按钮变「重试失败 1 条」",
           page.locator("#rv-up").inner_text().strip() == "重试失败 1 条",
           page.locator("#rv-up").inner_text())
        files_before = len(STATE["file_posts"])
        records_before = len(STATE["record_bodies"])
        page.locator("#rv-up").click()
        page.wait_for_function(
            "document.querySelectorAll('.rc-qi-st.done').length === 3", timeout=20000)
        ck("U264-06 只重试失败项、已成功不重传文件",
           len(STATE["file_posts"]) == files_before and len(STATE["record_bodies"]) == records_before + 1,
           {"files": len(STATE["file_posts"]), "records": len(STATE["record_bodies"])})
        page.locator("#rv-drop").click()
        page.locator("#ws-sources").wait_for(state="visible", timeout=20000)
        ctx.close()

        # ═════════════ U264-07：响应丢失 → 结果待确认 → 核对 ═════════════
        reset_state()
        STATE["plan"] = ["abort"]
        ctx = make_context(browser, viewport={"width": 1280, "height": 900})
        page = open_page(ctx, base, expect_weeks=False)
        page.wait_for_timeout(300)
        page.locator("#go-record").click()
        page.wait_for_timeout(200)
        select_files(page, [("丢回包.m4a", "audio/mp4")])
        page.wait_for_timeout(200)
        page.locator("#rv-up").click()
        page.wait_for_function(
            "document.getElementById('rv-up').textContent.trim() === '重新核对'", timeout=20000)
        ck("U264-07 响应丢失转「结果待确认」、不自动重传",
           page.locator(".rc-qi-st.unknown").count() == 1
           and page.locator("#rv-up").inner_text().strip() == "重新核对",
           page.locator("#rv-up").inner_text())
        # 服务器其实已落库：本人列表按 fileId 能核到
        STATE["extra_rows"] = [{"id": 9901, "memberId": 1, "fileId": 7001, "title": "丢回包",
                                "uploadedAt": "2026-11-16T10:31:00", "durationSec": 42,
                                "sizeBytes": 2048, "commentCount": 0, "memberName": "验证甲",
                                "sectionName": "二胡", "pieceTitle": None, "linked": False}]
        page.locator("#rv-up").click()
        page.wait_for_function("!!document.querySelector('.rc-qi-st.done')", timeout=20000)
        ck("U264-07 核对到记录即认成功、不再 POST",
           len(STATE["record_bodies"]) == 1, len(STATE["record_bodies"]))
        ctx.close()

        # ═════════════ L265：页面内录音分支 / reduced-motion ═════════════
        reset_state()
        ctx = make_context(browser, viewport={"width": 1280, "height": 900},
                           reduced_motion="reduce")
        page = open_page(ctx, base, expect_weeks=False)
        page.wait_for_timeout(300)
        page.locator("#go-record").click()
        page.wait_for_timeout(200)
        page.locator("#pick-record").click()
        page.wait_for_timeout(150)
        ck("L265 本机入口 → 录音分支：准备区 + 节拍器可见",
           page.locator("#ws-rec").is_visible() and page.locator("#ws-files").is_hidden()
           and page.locator("#metro-card").is_visible() and page.locator("#rec-start").is_visible())
        ck("L265 点来源只进准备区、不请求麦克风",
           page.locator("#recdemo").is_hidden())
        ck("L265 录音分支未录音时不显示命名表单", page.locator("#u-title").is_hidden())
        ck("A268-01 页面录音分支不显示「继续添加文件」", page.locator("#ws-add").is_hidden())
        # 模拟「停止录音」后的 pendingTake（headless 没有真麦克风；stageTake 是真实入口）
        page.evaluate("""() => {
          const f = new File([new Uint8Array([1,2,3])], 'take.m4a', {type: 'audio/mp4'});
          stageTake({source: 'rec', file: f, url: URL.createObjectURL(f), seconds: 4, bpms: [80]});
        }""")
        page.wait_for_timeout(250)
        ck("L265 页面内录音停止后命名表单可见可编辑",
           page.locator("#u-title").is_visible() and page.locator("#u-assign").is_visible(),
           {"titleVisible": page.locator("#u-title").is_visible(),
            "assignVisible": page.locator("#u-assign").is_visible()})
        page.fill("#u-title", "手写标题")
        ck("L265 录音标题改动进入预览与提交",
           page.input_value("#u-title") == "手写标题"
           and "手写标题" in page.locator("#rv-name").inner_text(),
           page.locator("#rv-name").inner_text())
        # 真提交一次：页面内录音的**手写标题**必须进到 POST /api/recordings 的 body
        page.locator("#rv-up").click()
        for _ in range(100):
            if STATE["record_bodies"]:
                break
            page.wait_for_timeout(50)
        bodies = STATE["record_bodies"]
        ck("L265 页面内录音提交时使用手写标题",
           len(bodies) == 1 and bodies[0].get("title") == "手写标题",
           bodies[:1])
        page.wait_for_timeout(300)
        ck("L265 录音上传成功后命名表单收回", page.locator("#u-title").is_hidden())
        anim = page.eval_on_selector("#ws-rec", "e => getComputedStyle(e).animationName")
        ck("L265 reduced-motion 下分支切换不动画", anim in ("none", ""), anim)
        ctx.close()

        browser.close()

    server.shutdown()
    print("\n==== 汇总 ====")
    failed = [c for c in CHECKS if not c["pass"]]
    print(f"检查 {len(CHECKS)} 项；通过 {len(CHECKS) - len(failed)}；失败 {len(failed)}；页面报错 {len(ERRORS)}")
    if ERRORS:
        print("页面报错：")
        for e in ERRORS:
            print("  " + e)
    return 0 if not failed and not ERRORS else 1


if __name__ == "__main__":
    sys.exit(run())
