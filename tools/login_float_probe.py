#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""#267 **生产登录页探针**（不是样张探针）。

跑在真实 `src/main/resources/static/login.html` 上：本地起静态服务器托管该目录，
`/api/auth/login` 由路由桩接管（不登录、不连数据库、不写真实业务数据）。
只量真实 DOM、真实事件、真实计算样式；不读 / 不记录任何真实凭据（只用假值）。

前置：无需启动 Spring Boot；Playwright 从 `design-samples/.deps-246` 借用（本机既有、已 gitignore）。
跑法：python -X utf8 tools/login_float_probe.py
失败退出码 1。

覆盖（对应 docs/14 §14.6.4「登录页文案修订」C267-01—08）：
  C267-01 误导句已删、建议改初始密码在
  C267-02 页脚联系管理员、无安装宣传（主标题 / 说明候选未定，不擅改）
  C267-03 三宽 / 日夜不横溢
  C267-04 登录失败文案、键盘 Tab / Enter、鼓手焦点反应、密码遮蔽不回归
  C267-06 两框独立焦点 / 输入 / 失焦保留 / 聚焦清空 / 空值失焦回落 / 框高与相邻控件稳定
  C267-07 无事件的程序赋值（模拟迟到填充）直接上浮
  C267-08 320 / 390 / 桌面、reduce 直接切态
未覆盖（真机 / 真自动填充，回执里如实列"未验"）：浏览器密码管理器 / 原生自动填充、
浏览器返回恢复、移动输入法 / 字号放大 / 读屏、日夜截图人工比对。
"""
import json
import sys
import threading
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
STATIC = ROOT / "src" / "main" / "resources" / "static"
sys.path.insert(0, str(ROOT / "design-samples" / ".deps-246"))
from playwright.sync_api import sync_playwright  # noqa: E402

CHECKS = []
ERRORS = []
LOGIN_BODIES = []


def ok(value, label, detail=None):
    CHECKS.append({"label": label, "ok": bool(value), "detail": detail})
    if not value:
        print("  ✗ " + label + ("" if detail is None else "  " + json.dumps(detail, ensure_ascii=False)))


class Handler(SimpleHTTPRequestHandler):
    def __init__(self, *a, **kw):
        super().__init__(*a, directory=str(STATIC), **kw)

    def log_message(self, *a):
        pass


def serve():
    srv = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    threading.Thread(target=srv.serve_forever, daemon=True).start()
    return srv


LABEL_STATE = """(el) => {
  const m = new DOMMatrixReadOnly(getComputedStyle(el).transform);
  const r = el.getBoundingClientRect();
  return { scale: m.a, shift: m.f, top: r.top, bottom: r.bottom,
           mid: (r.top + r.bottom) / 2, text: el.textContent.trim() };
}"""


def main():
    srv = serve()
    port = srv.server_address[1]
    base = "http://127.0.0.1:%d" % port
    try:
        with sync_playwright() as p:
            browser = p.chromium.launch(headless=True)
            context = browser.new_context(viewport={"width": 1056, "height": 950},
                                          color_scheme="light")
            page = context.new_page()
            page.on("pageerror", lambda e: ERRORS.append(str(e)))

            def stub_login(route):
                body = json.loads(route.request.post_data or "{}")
                LOGIN_BODIES.append(body)
                route.fulfill(status=200, content_type="application/json",
                              body=json.dumps({"code": 20001, "message": "账号或密码错误", "data": None},
                                              ensure_ascii=False))

            page.route("**/api/auth/login", stub_login)
            page.goto(base + "/login.html")
            page.wait_for_selector("#account")

            def wait_anim():
                page.wait_for_timeout(230)

            def is_float(page_obj, ident):
                st = page_obj.locator('label[for="%s"]' % ident).evaluate(LABEL_STATE)
                return st["scale"] < 0.8

            def label_state(page_obj, ident):
                return page_obj.locator('label[for="%s"]' % ident).evaluate(LABEL_STATE)

            # ---- C267-01 / 02 文案 ----
            body_text = page.locator("body").inner_text()
            ok("不用改密码" not in body_text and "无需改密码" not in body_text and "首次不用" not in body_text,
               "C267-01 误导句已从可见文案删除")
            ok("首次登录后，建议及时修改初始密码。" in body_text,
               "C267-01 已改为建议及时修改初始密码")
            ok("必须先改" not in body_text and "强制" not in body_text,
               "C267-01 未反向写成强制改密")
            ok("忘记密码或尚未获得账号，请联系管理员。" in body_text,
               "C267-02 页脚给出管理员帮助路径")
            ok("手机桌面" not in body_text, "C267-02 撤下登录前安装宣传")
            ok("排练安排、签到、请假" in body_text, "C267-02 未定候选主标题未被擅改")

            # ---- 标签语义 / 原契约 ----
            acc = page.locator("#account")
            pw = page.locator("#password")
            ok(label_state(page, "account")["text"] == "账号" and label_state(page, "password")["text"] == "密码",
               "两框标签为「账号」「密码」")
            ok(page.locator('label[for="account"]').count() == 1
               and page.locator('label[for="password"]').count() == 1,
               "真 label 与 for 关联保留")
            ok(acc.get_attribute("autocomplete") == "username"
               and pw.get_attribute("autocomplete") == "current-password"
               and pw.get_attribute("type") == "password"
               and acc.get_attribute("required") is not None
               and pw.get_attribute("required") is not None,
               "autocomplete / type=password / required 保留")

            # ---- C267-06 几何与状态 ----
            boxes = page.evaluate("""() => ({
              acc: document.querySelector('#account').getBoundingClientRect().toJSON(),
              pw: document.querySelector('#password').getBoundingClientRect().toJSON(),
              btn: document.querySelector('.seal-btn').getBoundingClientRect().toJSON(),
              ph: getComputedStyle(document.querySelector('#account')).paddingTop
            })""")
            ok(abs(boxes["acc"]["height"] - 56) <= 0.5 and abs(boxes["pw"]["height"] - 56) <= 0.5,
               "C267-06 两框高度 56px", boxes["acc"]["height"])
            for ident in ("account", "password"):
                st = label_state(page, ident)
                box = boxes["acc"] if ident == "account" else boxes["pw"]
                ok(abs(st["mid"] - (box["top"] + box["bottom"]) / 2) <= 2,
                   "C267-06 %s 空态标签垂直居中" % ident, st["mid"] - (box["top"] + box["bottom"]) / 2)
                ok(not is_float(page, ident), "C267-06 %s 初始不上浮" % ident)

            # 点标签 → 聚焦真 input，只浮这一框
            page.locator('label[for="account"]').click()
            wait_anim()
            ok(page.evaluate("() => document.activeElement.id") == "account", "点标签聚焦真 input")
            ok(is_float(page, "account") and not is_float(page, "password"),
               "C267-06 只浮聚焦的空框")
            btn_after = page.locator(".seal-btn").bounding_box()
            ok(abs(btn_after["y"] - boxes["btn"]["y"]) < 0.1
               and abs(btn_after["height"] - boxes["btn"]["height"]) < 0.1,
               "C267-06 聚焦不移动登录按钮")
            ok(page.locator("#art").evaluate("el => el.classList.contains('focus')"),
               "C267-04 鼓手对焦点有反应")
            st = label_state(page, "account")
            acc_box = page.locator("#account").bounding_box()
            ok(st["bottom"] <= acc_box["y"] + 24.5, "C267-06 上浮标签不压输入文字",
               {"labelBottom": st["bottom"], "contentTop": acc_box["y"] + 24})

            # 输入 → 失焦保持
            acc.fill("probe-account")
            page.locator("#password").focus()
            wait_anim()
            ok(is_float(page, "account") and is_float(page, "password"),
               "C267-06 有值失焦保持 + 聚焦即浮（无需先输入）")
            # 聚焦清空仍浮，空值失焦回落
            pw.fill("probe-only")
            page.locator("h1").click()
            wait_anim()
            ok(is_float(page, "account") and is_float(page, "password"), "C267-06 两框有值失焦都保持")
            ok(page.locator("#password").get_attribute("type") == "password", "C267-07 密码始终遮蔽")
            pw.fill("")
            wait_anim()
            ok(is_float(page, "password"), "C267-06 聚焦清空不回落")
            page.locator("h1").click()
            wait_anim()
            ok(not is_float(page, "password"), "C267-06 空值失焦回落")
            ok(is_float(page, "account"), "C267-06 另一框不受影响")

            # ---- C267-07 无事件的程序赋值（模拟迟到填充 / 返回恢复）----
            page.evaluate("() => { document.getElementById('password').value = 'late-fill' }")
            wait_anim()
            ok(is_float(page, "password"), "C267-07 迟到赋值无需点击即上浮")
            page.evaluate("() => { document.getElementById('password').value = '' }")
            wait_anim()
            ok(not is_float(page, "password"), "C267-07 无事件清空同样回落")

            # ---- C267-04 键盘 / 登录回归 ----
            page.locator("#account").focus()
            page.keyboard.press("Tab")
            ok(page.evaluate("() => document.activeElement.id") == "password", "C267-04 Tab 顺序保留")
            page.keyboard.press("Tab")
            ok(page.evaluate("() => document.activeElement.classList.contains('seal-btn')"),
               "C267-04 Tab 到登录按钮")
            acc.fill("probe-account")
            pw.fill("probe-only")
            page.keyboard.press("Enter")
            page.wait_for_timeout(250)
            ok(page.locator("#msg").inner_text().strip() == "账号或密码错误", "C267-04 错误登录文案不回归")
            ok(LOGIN_BODIES and LOGIN_BODIES[-1].get("account") == "probe-account"
               and LOGIN_BODIES[-1].get("password") == "probe-only",
               "C267-04 登录仍走原接口且请求体未变")

            # ---- C267-03 / 08 三宽 + reduce ----
            page.locator("#account").fill("")
            page.locator("#password").fill("")
            page.locator("h1").click()
            wait_anim()
            for width in (1024, 390, 320):
                page.set_viewport_size({"width": width, "height": 1000})
                wait_anim()
                layout = page.evaluate("""() => ({
                  view: innerWidth,
                  scroll: document.documentElement.scrollWidth,
                  widths: [document.querySelector('#account').getBoundingClientRect().width,
                           document.querySelector('#password').getBoundingClientRect().width],
                  heights: [document.querySelector('#account').getBoundingClientRect().height,
                            document.querySelector('#password').getBoundingClientRect().height]
                })""")
                ok(layout["scroll"] <= layout["view"] + 1
                   and all(h == 56 for h in layout["heights"]),
                   "C267-03 %dpx 不横溢、框高 56" % width, layout)
                for ident in ("account", "password"):
                    st = label_state(page, ident)
                    box = page.locator("#" + ident).bounding_box()
                    ok(abs(st["mid"] - (box["y"] + box["height"] / 2)) <= 2,
                       "C267-03 %dpx %s 空态标签仍居中" % (width, ident),
                       st["mid"] - (box["y"] + box["height"] / 2))
            page.set_viewport_size({"width": 1056, "height": 950})

            # 登录页不自带主题切换；夜间态由全站令牌 `html[data-theme=dark]` 决定（band-nav 首帧会打上）。
            # 这里模拟同一状态，量的是令牌换色后的对比度，不是 prefers-color-scheme。
            page.evaluate("() => { document.documentElement.dataset.theme = 'dark' }")
            page.locator("#account").fill("")
            page.locator("#password").fill("")
            page.locator("#password").focus()
            wait_anim()
            ok(is_float(page, "password"), "C267-08 夜间聚焦照常上浮")
            contrast = page.evaluate("""() => {
              const el = document.querySelector('#account');
              const s = getComputedStyle(el);
              const lum = c => {
                const v = c.match(/[\\d.]+/g).slice(0, 3).map(x => x / 255)
                  .map(x => x <= .03928 ? x / 12.92 : Math.pow((x + .055) / 1.055, 2.4));
                return .2126 * v[0] + .7152 * v[1] + .0722 * v[2];
              };
              const a = lum(s.backgroundColor), b = lum(s.color);
              return { ratio: (Math.max(a, b) + .05) / (Math.min(a, b) + .05),
                       bg: s.backgroundColor, fg: s.color };
            }""")
            ok(contrast["ratio"] >= 4.5, "C267-03 夜间输入框底色 / 字色对比度 ≥ 4.5", contrast)

            page.evaluate("() => { document.documentElement.dataset.theme = 'light' }")
            page.emulate_media(reduced_motion="reduce")
            page.locator("h1").click()
            page.locator("#password").fill("")
            page.locator("#password").focus()
            duration = page.locator('label[for="password"]').evaluate(
                "el => getComputedStyle(el).transitionDuration")
            ok(is_float(page, "password"), "C267-08 reduce 直接切到上浮态")
            ok(duration in ("0s", "0ms"), "C267-08 reduce 无过渡动画", duration)
            art_anim = page.locator("#armA").evaluate("el => getComputedStyle(el).animationName")
            ok(art_anim == "none", "C267-04 reduce 下插画仍静态")

            ok(not ERRORS, "运行时错误为 0", ERRORS)

            # 截图只落本机 design-samples/（已 gitignore），供人眼比对观感
            shots = ROOT / "design-samples" / "shots-267"
            shots.mkdir(parents=True, exist_ok=True)
            page.emulate_media(reduced_motion="no-preference")
            page.locator("#account").fill("")
            page.locator("#password").fill("")
            page.locator("h1").click()
            page.evaluate("() => { document.querySelector('#msg').textContent = '' }")
            for width in (1024, 390, 320):
                page.set_viewport_size({"width": width, "height": 900})
                wait_anim()
                page.screenshot(path=str(shots / ("login-%d.png" % width)), full_page=True)
            page.set_viewport_size({"width": 390, "height": 900})
            page.evaluate("() => { document.documentElement.dataset.theme = 'dark' }")
            page.locator("#password").focus()
            wait_anim()
            page.screenshot(path=str(shots / "login-390-dark-focus.png"), full_page=True)
            context.close()
            browser.close()
    finally:
        srv.shutdown()

    passed = sum(1 for c in CHECKS if c["ok"])
    failed = len(CHECKS) - passed
    print(json.dumps({"passed": passed, "failed": failed, "errors": ERRORS}, ensure_ascii=False))
    if failed:
        print("失败项：")
        for c in CHECKS:
            if not c["ok"]:
                print("  - " + c["label"])
        sys.exit(1)


if __name__ == "__main__":
    main()
