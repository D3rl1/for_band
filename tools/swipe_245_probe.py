"""#245 生产探针：右滑续接 + 完全展开后的轻触反馈。

跑法：python tools/swipe_245_probe.py
不需要后端：用 `tools/profile_navigation_fixture.py` 的隔离接口夹具。
量的是真实生产 `band-shell.js` / `band.css` 在 390×844 触屏环境里的行为：
- 松手续接时长随剩余距离 / 近端速度变化（内联 transitionDuration 与逐帧位移）；
- 50% / 85% / 95%、快慢、20% 未到位、90% 取消；
- 有效全开震动 1 次；未到位 / 取消 / 立即关闭 / 偏好关闭 / 无能力 0 次；
- 减少动态效果：0 时长仍按实际展开状态给 1 次；
- 回归：抽屉滚动锁、菜单可点、头像点击开合。
⚠️ 隔离浏览器证据，不是真机绘制性能 / 真实震动结论。
"""
from __future__ import annotations

import json
import sys
import threading
import time
from pathlib import Path

sys.stdout.reconfigure(encoding='utf-8', errors='replace')

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / 'tools'))
import profile_navigation_fixture as fx  # noqa: E402
from playwright.sync_api import sync_playwright  # noqa: E402

OUT = ROOT / 'design-samples/shots-swipe-245'
OUT.mkdir(parents=True, exist_ok=True)
BAD: list[str] = []
TOTAL = [0]
REPORT: dict = {}


def check(name: str, ok: bool, extra=None) -> None:
    TOTAL[0] += 1
    print(('  OK   ' if ok else '  FAIL ') + name + ('' if extra is None else '   | ' + str(extra)))
    if not ok:
        BAD.append(name)


def sample_start(page) -> None:
    page.evaluate("""() => {
      const rail = document.querySelector('.railwrap');
      const x = () => new DOMMatrixReadOnly(getComputedStyle(rail).transform).m41;
      window.__sw = {t0: performance.now(), frames: [], startX: x()};
      const tick = () => {
        window.__sw.frames.push({t: performance.now(), x: x()});
        if (performance.now() - window.__sw.t0 < 700) requestAnimationFrame(tick);
      };
      requestAnimationFrame(tick);
    }""")


def sample_result(page) -> dict:
    return page.evaluate("""() => {
      const s = window.__sw, f = s.frames;
      const at = s.t0 + 49;
      let f49 = f[0] || {t: s.t0, x: s.startX};
      for (const fr of f) { if (fr.t <= at) f49 = fr; else break; }
      let endT = null;
      for (const fr of f) {
        if (Math.abs(fr.x) < 0.5) { endT = fr.t - s.t0; break; }
      }
      return {startX: +s.startX.toFixed(2), first49: +(f49.x - s.startX).toFixed(2),
              timeToEnd: endT === null ? null : Math.round(endT), frames: f.length,
              maxGap: f.length > 1 ? Math.max(...f.slice(1).map((fr, i) => fr.t - f[i].t)) : 0};
    }""")


def drag(cdp, page, pct: float, speed: str = 'fast', cancel: bool = False) -> dict:
    """拖到 pct 后松手（或取消）。

    在 `document.body` 上派发真实 TouchEvent：起点不受页面元素命中测试影响，
    生产监听器、CSS 过渡与 rAF 采样都是真的；只是不经过浏览器合成器的命中判定。
    """
    res = page.evaluate("""async ({pct, speed, cancel}) => {
      const rail = document.querySelector('.railwrap');
      const openW = rail.offsetWidth || 300;
      const mk = (type, x) => {
        const t = new Touch({identifier: 1, target: document.body, clientX: x, clientY: 420});
        return new TouchEvent(type, {
          bubbles: true, cancelable: true,
          touches: (type === 'touchend' || type === 'touchcancel') ? [] : [t],
          changedTouches: [t], targetTouches: []
        });
      };
      const fire = (type, x) => document.body.dispatchEvent(mk(type, x));
      const x0 = 40, x1 = x0 + openW * pct / 100;
      const steps = speed === 'fast' ? 7 : 14;   // 快甩 = 少步数、高速度
      fire('touchstart', x0);
      for (let i = 1; i <= steps; i++) {
        fire('touchmove', x0 + (x1 - x0) * i / steps);
        await new Promise(r => setTimeout(r, speed === 'fast' ? 16 : 45));
      }
      if (speed === 'slow') await new Promise(r => setTimeout(r, 280));
      const xnow = () => new DOMMatrixReadOnly(getComputedStyle(rail).transform).m41;
      window.__sw = {t0: performance.now(), frames: [], startX: xnow()};
      const tick = () => {
        window.__sw.frames.push({t: performance.now(), x: xnow()});
        if (performance.now() - window.__sw.t0 < 700) requestAnimationFrame(tick);
      };
      requestAnimationFrame(tick);
      fire(cancel ? 'touchcancel' : 'touchend', x1);
      await new Promise(r => setTimeout(r, 30));
      const raw = rail.style.transitionDuration || '0s';
      const ms = raw.trim().endsWith('ms') ? parseFloat(raw) : parseFloat(raw) * 1000;
      await new Promise(r => setTimeout(r, 740));
      return {requestedMs: Math.round(ms || 0)};
    }""", {'pct': pct, 'speed': speed, 'cancel': cancel})
    out = sample_result(page)
    out['requestedMs'] = res['requestedMs']
    out['open'] = page.evaluate("document.documentElement.classList.contains('drawer-open')")
    return out


def reset(page, haptics: str | None = None) -> None:
    page.evaluate("""(h) => {
      if (h === null) localStorage.removeItem('band_haptics'); else localStorage.setItem('band_haptics', h);
      window.__vib = 0;
      const root = document.documentElement;
      root.classList.remove('drawer-open');
      const rail = document.querySelector('.railwrap');
      if (rail) { rail.style.transition = ''; rail.style.transform = ''; }
      document.querySelector('.scrim').style.opacity = '';
      document.querySelector('.mav-toggle').setAttribute('aria-expanded', 'false');
    }""", haptics)
    page.wait_for_timeout(120)


def vib(page) -> int:
    return page.evaluate("window.__vib || 0")


def main() -> int:
    server = fx.ThreadingHTTPServer(('127.0.0.1', 0), fx.Handler)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    base = f'http://127.0.0.1:{server.server_port}'
    try:
        with sync_playwright() as pw:
            browser = pw.chromium.launch()
            ctx = browser.new_context(viewport={'width': 390, 'height': 844}, has_touch=True,
                                      is_mobile=True, device_scale_factor=2)
            ctx.add_init_script("""
              localStorage.setItem('band_token', 'isolated-fixture');
              sessionStorage.removeItem('band_welcome');
              window.__vib = 0;
              window.BandNative = { openDrawerFeedback: function () { window.__vib++; } };
            """)
            ctx.route('**/api/**', fx.api)
            page = ctx.new_page()
            errors: list[str] = []
            page.on('pageerror', lambda e: errors.append(str(e)))
            page.on('console', lambda m: errors.append('console:' + m.text) if m.type == 'error' else None)
            page.goto(base + '/events.html')
            page.wait_for_selector('.mav-toggle', timeout=8000)
            page.wait_for_timeout(500)
            cdp = ctx.new_cdp_session(page)

            check('页面加载无 JS 报错', not errors, errors[:3])

            print('\nA. 续接：剩余距离 + 近端速度')
            runs = {}
            for pct, speed in [(50, 'slow'), (85, 'fast'), (95, 'fast'), (95, 'slow')]:
                reset(page)
                m = drag(cdp, page, pct, speed)
                runs[f'{pct}_{speed}'] = m
                check(f'{pct}% {speed} 打开', m['open'], m)
                check(f'{pct}% {speed} 无 JS 报错', not errors, errors[-1:] if errors else None)
            fast95 = runs['95_fast']
            slow50 = runs['50_slow']
            check('95% 快甩续接明显短于 340ms', 0 < fast95['requestedMs'] < 200, fast95)
            check('95% 快甩前 49ms 明显移动（非静止起步）', fast95['first49'] > 5, fast95)
            check('95% 快甩到终点 < 200ms', fast95['timeToEnd'] is not None and fast95['timeToEnd'] < 200, fast95)
            check('50% 慢放走满 340ms（距离主导）', slow50['requestedMs'] == 340, slow50)
            check('50% 慢放到终点约 300–400ms', slow50['timeToEnd'] is not None and 280 <= slow50['timeToEnd'] <= 420, slow50)
            check('所有样本帧间隔 < 40ms（隔离浏览器）',
                  max(r['maxGap'] for r in runs.values()) < 40, {k: round(v['maxGap'], 1) for k, v in runs.items()})
            REPORT['runs'] = runs

            print('\nB. 开判定 / 取消 / 未到位')
            reset(page)
            m20 = drag(cdp, page, 20, 'slow')
            check('20% 慢放回收不打开', not m20['open'], m20)
            reset(page)
            m90c = drag(cdp, page, 90, 'fast', cancel=True)
            check('90% 取消回收不打开', not m90c['open'], m90c)
            reset(page)
            m30 = drag(cdp, page, 30, 'fast')
            check('30% 快甩按速度打开', m30['open'], m30)

            print('\nC. 触觉：有效全开 1 次，其余 0 次')
            reset(page)
            drag(cdp, page, 95, 'fast')
            page.wait_for_timeout(500)
            check('有效全开震动 1 次', vib(page) == 1, vib(page))
            reset(page)
            drag(cdp, page, 15, 'slow')
            page.wait_for_timeout(500)
            check('未到位不震动', vib(page) == 0, vib(page))
            reset(page)
            drag(cdp, page, 90, 'fast', cancel=True)
            page.wait_for_timeout(500)
            check('取消不震动', vib(page) == 0, vib(page))
            # 完整打开后 45ms 立即关闭：旧回调不得补震
            reset(page)
            page.evaluate("""async () => {
              const rail = document.querySelector('.railwrap');
              const openW = rail.offsetWidth || 300;
              const mk = (type, x) => {
                const t = new Touch({identifier: 1, target: document.body, clientX: x, clientY: 420});
                return new TouchEvent(type, {bubbles: true, cancelable: true,
                  touches: type === 'touchend' ? [] : [t], changedTouches: [t], targetTouches: []});
              };
              const fire = (type, x) => document.body.dispatchEvent(mk(type, x));
              const x0 = 40, x1 = x0 + openW * .95;
              fire('touchstart', x0);
              for (let i = 1; i <= 14; i++) {
                fire('touchmove', x0 + (x1 - x0) * i / 14);
                await new Promise(r => setTimeout(r, 16));
              }
              fire('touchend', x1);
            }""")
            page.wait_for_timeout(45)
            # 抽屉已盖住头像按钮；这里走 DOM click（同一监听器），不依赖命中测试
            page.evaluate("document.querySelector('.mav-toggle').click()")
            page.wait_for_timeout(600)
            check('全开后立即关闭不补震', vib(page) == 0, vib(page))
            # 偏好关闭
            reset(page, '0')
            drag(cdp, page, 95, 'fast')
            page.wait_for_timeout(500)
            check('偏好关闭 → 不震动', vib(page) == 0, vib(page))
            # 无能力
            reset(page, None)
            page.evaluate("delete window.BandNative; try { Object.defineProperty(Navigator.prototype,'vibrate',{configurable:true,value:undefined}); } catch(e) {}")
            mUn = drag(cdp, page, 95, 'fast')
            page.wait_for_timeout(500)
            check('无能力 → 静默降级且抽屉仍开',
                  page.evaluate("document.documentElement.classList.contains('drawer-open')"),
                  {'drag': mUn, 'vib': vib(page),
                   'supported': page.evaluate("BAND.hapticSupported()")})
            page.evaluate("window.BandNative = { openDrawerFeedback: function(){ window.__vib++; } }")

            print('\nD. 头像点击 / 回归')
            reset(page)
            page.locator('.mav-toggle').click()
            page.wait_for_timeout(500)
            check('头像点击打开抽屉', page.evaluate("document.documentElement.classList.contains('drawer-open')"))
            check('头像点击有效全开震动 1 次', vib(page) == 1, vib(page))
            check('打开时锁主页面滚动', page.evaluate(
                "getComputedStyle(document.documentElement).overflow === 'hidden' && getComputedStyle(document.body).overflow === 'hidden'"))
            before_close = vib(page)
            page.evaluate("document.querySelector('.mav-toggle').click()")
            page.wait_for_timeout(450)
            check('再次点击关闭', not page.evaluate("document.documentElement.classList.contains('drawer-open')"))
            check('关闭不新增震动', vib(page) == before_close, {'before': before_close, 'after': vib(page)})

            print('\nE. 减少动态效果：0 时长仍按实际展开状态给 1 次')
            ctx2 = browser.new_context(viewport={'width': 390, 'height': 844}, has_touch=True,
                                       is_mobile=True, reduced_motion='reduce')
            ctx2.add_init_script("""
              localStorage.setItem('band_token', 'isolated-fixture');
              sessionStorage.removeItem('band_welcome');
              window.__vib = 0;
              window.BandNative = { openDrawerFeedback: function () { window.__vib++; } };
            """)
            ctx2.route('**/api/**', fx.api)
            p2 = ctx2.new_page()
            p2.goto(base + '/events.html')
            p2.wait_for_selector('.mav-toggle', timeout=8000)
            p2.wait_for_timeout(400)
            cdp2 = ctx2.new_cdp_session(p2)
            m2 = drag(cdp2, p2, 95, 'fast')
            p2.wait_for_timeout(500)
            check('reduced：抽屉仍打开', p2.evaluate("document.documentElement.classList.contains('drawer-open')"))
            check('reduced：震动仍 1 次', p2.evaluate("window.__vib") == 1, p2.evaluate("window.__vib"))
            check('reduced：0 时长续接', m2['requestedMs'] == 0, m2)

            check('全程无 JS 报错', not errors, errors[:5])
            REPORT['pageErrors'] = errors
            (OUT / 'swipe245-production-audit.json').write_text(
                json.dumps(REPORT, ensure_ascii=False, indent=2), encoding='utf-8')
            browser.close()
    finally:
        server.shutdown()
    print(f'\n合计 {TOTAL[0] - len(BAD)}/{TOTAL[0]} 通过；失败：{BAD or "无"}')
    return 1 if BAD else 0


if __name__ == '__main__':
    raise SystemExit(main())
