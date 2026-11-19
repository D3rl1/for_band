#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""#258 节拍器响度（8ms 峰值保持包络）**生产探针**。

跑在真实 Spring Boot 静态页上（`/band-core.js`、`/sounds/*`、`metronome.html`、
`recordings.html`），接口一律走 Playwright 路由桩：**不连数据库、不写业务数据**。

覆盖：
  ① 兼容模式 WAV：参数 / 峰值 / RMS / SHA256（= 点验基准）；
  ② 高精度 `BAND.tick`：频率、峰值、增益自动化四时刻（含 0.012s 保持段）、0.080s 停声；
  ③ 离线渲染：80ms 窗 RMS 与 WAV 差 ≤0.5dB、8ms 处包络仍在峰值、240BPM 十六分叠音不削波；
  ④ 兼容音池：新 `-258` URL、每种 4 个元素、volume=1、轮换；
  ⑤ 两页共用同一份 `band-core.js`，且服务端下发的 JS / WAV 哈希与磁盘一致。

⚠️ 证明不了「耳朵听得到」：真机外放 / 耳机 / 乐器同时演奏的听感仍留人工（M258-05）。
前置：应用已在 :8080 运行；Playwright 从 design-samples/.deps-246 借用（本机既有依赖）。
跑法：python tools/metro_loudness_probe.py
失败退出码 1。
"""
import hashlib
import json
import math
import os
import sys
import urllib.request
import wave
from pathlib import Path

try:
    from playwright.sync_api import sync_playwright
except ImportError:  # 本机既有依赖在 design-samples/.deps-246（已 gitignore）
    sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "design-samples" / ".deps-246"))
    from playwright.sync_api import sync_playwright

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:  # noqa: BLE001
    pass

BASE = os.environ.get("BAND_BASE", "http://localhost:8080")
ROOT = Path(__file__).resolve().parent.parent
SOUNDS = ROOT / "src" / "main" / "resources" / "static" / "sounds"

ACCENT = "metro-accent-258.wav"
WEAK = "metro-weak-258.wav"
EXPECTED_SHA = {
    ACCENT: "5ae20003dd967d70fcdcaf3e596fbc6be679376277305709d81528f79730a474",
    WEAK: "5c6e228805ae4743efd688865fb5a9a2356a04b483bdbfe7f07b05760ab84bb0",
}

failures = []
total = [0]


def check(name, ok, extra=""):
    total[0] += 1
    print(("  OK   " if ok else "  FAIL ") + name + (("   | " + str(extra)) if extra != "" else ""))
    if not ok:
        failures.append(name)


def section(title):
    print("\n" + "=" * 78 + "\n" + title + "\n" + "=" * 78)


def sha256_file(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def sha256_http(path):
    with urllib.request.urlopen(BASE + path, timeout=10) as r:
        return hashlib.sha256(r.read()).hexdigest()


def read_mono(path):
    with wave.open(str(path), "rb") as w:
        params = w.getparams()
        raw = w.readframes(w.getnframes())
    vals = [int.from_bytes(raw[i:i + 2], "little", signed=True) / 32768.0
            for i in range(0, len(raw), 2)]
    return params, vals


def db(x, y):
    return 20 * math.log10(x / y)


def mock_api(ctx):
    def handler(route):
        url = route.request.url
        if url.endswith("/api/auth/me"):
            body = {"code": 0, "message": "ok",
                    "data": {"id": 1, "name": "测试团员", "role": "ADMIN",
                             "sectionName": "弹拨声部"}}
        else:
            body = {"code": 0, "message": "ok", "data": []}
        route.fulfill(status=200, content_type="application/json",
                      body=json.dumps(body, ensure_ascii=False))

    ctx.route("**/api/**", handler)


def open_page(browser, path):
    ctx = browser.new_context(viewport={"width": 1280, "height": 900}, service_workers="block")
    ctx.add_init_script("try{localStorage.setItem('band_token','probe-token')}catch(e){}")
    page = ctx.new_page()
    errs = []
    page.on("pageerror", lambda e: errs.append(str(e)))
    mock_api(ctx)
    page.route("**/vendor/oneko/oneko.js",
               lambda r: r.fulfill(status=200, content_type="application/javascript", body="void 0;"))
    page.goto(BASE + path, wait_until="domcontentloaded")
    page.wait_for_selector(".layout", timeout=8000)
    page.wait_for_timeout(300)
    return ctx, page, errs


def schedule(page):
    return page.evaluate("""() => {
      const log = { frames: [], oscs: [], gains: [], ctors: 0 };
      class P {
        constructor() { this.events = []; }
        setValueAtTime(v, t) { this.events.push(['set', v, t]); return this; }
        exponentialRampToValueAtTime(v, t) { this.events.push(['exp', v, t]); return this; }
        linearRampToValueAtTime(v, t) { this.events.push(['lin', v, t]); return this; }
      }
      const ac = {
        state: 'running', currentTime: 0, destination: {},
        resume() { return Promise.resolve(); },
        createOscillator() {
          const o = { type: '', frequency: { value: 0 }, connect() {},
                      start(t) { log.oscs.push(['start', t]); },
                      stop(t) { log.oscs.push(['stop', t]); } };
          log.frames.push(o.frequency);
          return o;
        },
        createGain() { const g = { gain: new P(), connect() {} }; log.gains.push(g); return g; },
      };
      const saved = BAND._ac;
      const Real = window.AudioContext;
      window.AudioContext = function () { log.ctors++; };
      BAND._ac = ac;
      BAND.tick(true, 5);
      BAND.tick(false, 5);
      BAND._ac = saved;
      window.AudioContext = Real;
      return { ctors: log.ctors, freqs: log.frames.map(f => f.value),
               events: log.gains.map(g => g.gain.events), oscs: log.oscs };
    }""")


RENDER_JS = """
async ({ accent, when, windowSec }) => {
  const sr = 44100;
  const dur = Math.ceil(sr * 1.2);
  const off = new OfflineAudioContext(1, dur, sr);
  off.resume = () => Promise.resolve();
  const saved = BAND._ac;
  BAND._ac = off;
  BAND.tick(accent, when);
  BAND._ac = saved;
  const buf = await off.startRendering();
  const d = buf.getChannelData(0);
  const a = Math.round(when * sr), b = Math.round((when + windowSec) * sr);
  let peak = 0, sum = 0;
  for (let i = a; i < b; i++) { const v = d[i]; if (Math.abs(v) > peak) peak = Math.abs(v); sum += v * v; }
  // 8ms 处的短时振幅（2ms 窗 RMS × √2 ≈ 包络值）
  const c0 = Math.round((when + 0.007) * sr), c1 = Math.round((when + 0.009) * sr);
  let s2 = 0;
  for (let i = c0; i < c1; i++) s2 += d[i] * d[i];
  const env8 = Math.sqrt(s2 / Math.max(1, c1 - c0)) * Math.SQRT2;
  // 4ms 前的包络（起音段对照）
  const e0 = Math.round((when + 0.001) * sr), e1 = Math.round((when + 0.003) * sr);
  let s3 = 0;
  for (let i = e0; i < e1; i++) s3 += d[i] * d[i];
  const env2 = Math.sqrt(s3 / Math.max(1, e1 - e0)) * Math.SQRT2;
  return { peak, rms: Math.sqrt(sum / Math.max(1, b - a)), env8, env2 };
}
"""

CLIP_JS = """
async ({ bpm, division, accentEvery, seconds }) => {
  const sr = 44100;
  const off = new OfflineAudioContext(1, Math.ceil(sr * (seconds + 0.2)), sr);
  off.resume = () => Promise.resolve();
  const saved = BAND._ac;
  BAND._ac = off;
  const step = 60 / bpm / division;
  const n = Math.round(seconds / step);
  for (let i = 0; i < n; i++) BAND.tick(accentEvery ? true : (i % accentEvery === 0), 0.05 + i * step);
  BAND._ac = saved;
  const buf = await off.startRendering();
  const d = buf.getChannelData(0);
  let peak = 0, clipped = 0;
  for (let i = 0; i < d.length; i++) {
    const v = Math.abs(d[i]);
    if (v > peak) peak = v;
    if (v >= 0.999) clipped++;
  }
  return { peak, clipped, beats: n };
}
"""


def main():
    section("① 兼容模式音源（磁盘）")
    wav = {}
    for name, peak_want in ((ACCENT, 0.90), (WEAK, 0.45)):
        path = SOUNDS / name
        check(f"{name} 存在", path.exists(), str(path))
        if not path.exists():
            continue
        params, vals = read_mono(path)
        wav[name] = vals
        dur = len(vals) / params.framerate
        check(f"{name} 参数 44.1kHz / 单声道 / 16-bit / 80ms",
              (params.framerate, params.nchannels, params.sampwidth) == (44100, 1, 2)
              and abs(dur - 0.08) < 1e-3, (params.framerate, params.nchannels, params.sampwidth, dur))
        peak = max(abs(v) for v in vals)
        check(f"{name} 峰值 {peak_want:.2f}", abs(peak - peak_want) < 1e-3, peak)
        check(f"{name} SHA256 = 点验基准", sha256_file(path) == EXPECTED_SHA[name], sha256_file(path)[:16])

    rms_wav = {n: math.sqrt(sum(v * v for v in vals) / len(vals)) for n, vals in wav.items()}
    check("两条音源 RMS 有 2:1 强弱关系（±0.5dB）",
          abs(db(rms_wav[ACCENT], rms_wav[WEAK]) - db(0.90, 0.45)) <= 0.5,
          (round(rms_wav[ACCENT], 5), round(rms_wav[WEAK], 5)))

    # 旧资源过渡保留（#258 只改引用，不删旧文件），并量出实际提升量。
    legacy = {"accent": SOUNDS / "metro-accent.wav", "weak": SOUNDS / "metro-weak.wav"}
    check("旧 metro-accent.wav / metro-weak.wav 仍在（过渡保留）",
          all(p.exists() for p in legacy.values()), [p.name for p in legacy.values()])
    for key, name in (("accent", ACCENT), ("weak", WEAK)):
        if not legacy[key].exists() or name not in wav:
            continue
        _, old = read_mono(legacy[key])
        old = old + [0.0] * (len(wav[name]) - len(old))
        gain = db(rms_wav[name], math.sqrt(sum(v * v for v in old) / len(old)))
        want = 3.79 if key == "accent" else 4.11
        check(f"{key} 80ms 窗 RMS 相对旧音 +{want}dB（±0.3）", abs(gain - want) <= 0.3, round(gain, 2))

    with sync_playwright() as pw:
        browser = pw.chromium.launch()

        ctx, page, errs = open_page(browser, "/metronome.html")

        section("② 高精度 BAND.tick 的增益自动化（结构判据）")
        sch = schedule(page)
        check("tick 不自建 AudioContext（全页唯一 AudioContext）", sch["ctors"] == 0, sch["ctors"])
        check("两拍各一次振荡器，频率 1600 / 1000", sch["freqs"] == [1600, 1000], sch["freqs"])
        ev_a, ev_w = sch["events"][0], sch["events"][1]

        def fmt(events):
            return [(k, round(v, 4), round(t, 4)) for k, v, t in events]

        want_a = [("set", 0.0001, 5.0), ("exp", 0.90, 5.004),
                  ("set", 0.90, 5.012), ("exp", 0.0001, 5.06)]
        want_w = [("set", 0.0001, 5.0), ("exp", 0.45, 5.004),
                  ("set", 0.45, 5.012), ("exp", 0.0001, 5.06)]
        check("重拍自动化四时刻正确（含 0.012s 保持段）", fmt(ev_a) == want_a, fmt(ev_a))
        check("弱拍自动化四时刻正确（含 0.012s 保持段）", fmt(ev_w) == want_w, fmt(ev_w))
        check("保持段是 8ms 不是 24ms（没有 t+0.028 事件）",
              all(abs(t - 5.028) > 1e-6 for _, _, t in ev_a + ev_w), "")
        stops = [t for k, t in sch["oscs"] if k == "stop"]
        starts = [t for k, t in sch["oscs"] if k == "start"]
        check("振荡器 0.080s 停声", [round(x, 4) for x in stops] == [5.08, 5.08], stops)
        check("振荡器同刻起声", [round(x, 4) for x in starts] == [5.0, 5.0], starts)

        section("③ 离线渲染：响度、保持段、叠音不削波")
        r_a = page.evaluate(RENDER_JS, {"accent": True, "when": 0.2, "windowSec": 0.08})
        r_w = page.evaluate(RENDER_JS, {"accent": False, "when": 0.2, "windowSec": 0.08})
        d_a = db(r_a["rms"], rms_wav.get(ACCENT, r_a["rms"]))
        d_w = db(r_w["rms"], rms_wav.get(WEAK, r_w["rms"]))
        check("重拍：离线渲染与 WAV 同窗口 RMS 差 ≤0.5dB", abs(d_a) <= 0.5, round(d_a, 3))
        check("弱拍：离线渲染与 WAV 同窗口 RMS 差 ≤0.5dB", abs(d_w) <= 0.5, round(d_w, 3))
        check("重拍渲染峰值 ≈0.90", abs(r_a["peak"] - 0.90) < 0.02, round(r_a["peak"], 4))
        check("弱拍渲染峰值 ≈0.45", abs(r_w["peak"] - 0.45) < 0.02, round(r_w["peak"], 4))
        check("8ms 处包络仍在峰值（8ms 保持段）", r_a["env8"] > 0.75,
              (round(r_a["env8"], 4), round(r_w["env8"], 4)))
        check("起音段 2ms 处确实还小（不是一开始就满）", r_a["env2"] < 0.6, round(r_a["env2"], 4))

        clip16 = page.evaluate(CLIP_JS, {"bpm": 240, "division": 4, "accentEvery": 1, "seconds": 1.0})
        check("240BPM 十六分全重拍叠音无削波（峰值 <1，越界样本 0）",
              clip16["peak"] < 1.0 and clip16["clipped"] == 0,
              (round(clip16["peak"], 5), clip16["clipped"], clip16["beats"]))
        clipmix = page.evaluate(CLIP_JS, {"bpm": 240, "division": 4, "accentEvery": 4, "seconds": 1.0})
        check("240BPM 十六分强弱混合无削波",
              clipmix["peak"] < 1.0 and clipmix["clipped"] == 0, round(clipmix["peak"], 5))

        section("④ 兼容模式音池")
        pool = page.evaluate("""() => {
          BAND._clickPool = null;
          const p = BAND.warmClickPool();
          return {
            accent: p.accent.map(a => a.getAttribute('src')),
            weak: p.weak.map(a => a.getAttribute('src')),
            vol: [...p.accent, ...p.weak].map(a => a.volume),
            preload: [...p.accent, ...p.weak].map(a => a.preload),
            idx: p.idx,
          };
        }""")
        check("重拍音池指向 /sounds/metro-accent-258.wav",
              pool["accent"] == ["/sounds/metro-accent-258.wav"] * 4, pool["accent"])
        check("弱拍音池指向 /sounds/metro-weak-258.wav",
              pool["weak"] == ["/sounds/metro-weak-258.wav"] * 4, pool["weak"])
        check("8 个元素 volume=1、preload=auto",
              set(pool["vol"]) == {1} and set(pool["preload"]) == {"auto"},
              (pool["vol"][:2], pool["preload"][:2]))
        rotation = page.evaluate("""() => {
          const out = [];
          for (let i = 0; i < 6; i++) { BAND.tickCompat(true); out.push(BAND._clickPool.idx.accent); }
          return out;
        }""")
        check("兼容模式轮换 4 个元素（索引 1,2,3,0,1,2）", rotation == [1, 2, 3, 0, 1, 2], rotation)

        section("⑤ 两页一致 / 服务端下发哈希")
        hold_metro = page.evaluate("() => BAND.tick.toString().includes('t + 0.012')")
        check("独立节拍器页的 tick 含 8ms 保持段", hold_metro is True, hold_metro)
        ctx.close()

        ctx2, page2, errs2 = open_page(browser, "/recordings.html")
        hold_rec = page2.evaluate("() => BAND.tick.toString().includes('t + 0.012')")
        pool_rec = page2.evaluate("() => BAND.warmClickPool().accent[0].getAttribute('src')")
        check("录音页的 tick 也含保持段（同一份 band-core.js）", hold_rec is True, hold_rec)
        check("录音页兼容音池同样指向 -258", pool_rec == "/sounds/metro-accent-258.wav", pool_rec)
        ctx2.close()

        errs.extend(errs2)
        check("两页 JS 错误 0", errs == [], errs[:3])

        section("⑦ PWA：新 URL 让 SW 不会拿旧点击音凑数（M258-04）")
        ctx3 = browser.new_context(viewport={"width": 1280, "height": 900}, service_workers="allow")
        ctx3.add_init_script("try{localStorage.setItem('band_token','probe-token')}catch(e){}")
        mock_api(ctx3)
        p3 = ctx3.new_page()
        p3.goto(BASE + "/metronome.html", wait_until="domcontentloaded")
        p3.wait_for_function("() => navigator.serviceWorker && navigator.serviceWorker.ready", timeout=10000)
        p3.wait_for_timeout(900)   # 等安装 / 激活
        online = p3.evaluate("""async () => {
          const out = {};
          for (const u of ['/sounds/metro-accent-258.wav', '/sounds/metro-weak-258.wav',
                           '/sounds/metro-accent.wav']) {
            const r = await fetch(u);
            out[u] = r.status;
          }
          await new Promise(r => setTimeout(r, 700));   // 等 stale-while-revalidate 落缓存
          return out;
        }""")
        check("联网取两条新音源 200（旧 URL 仍 200，过渡保留）",
              set(online.values()) == {200}, online)
        ctx3.set_offline(True)
        offline = p3.evaluate("""async () => {
          const out = {};
          for (const u of ['/sounds/metro-accent-258.wav', '/sounds/metro-weak-258.wav']) {
            try { const r = await fetch(u); out[u] = r.status; } catch (e) { out[u] = 'ERR'; }
          }
          return out;
        }""")
        check("断网后两条新音源仍可取（先成功加载过，离线才有；不承诺未缓存资源）",
              set(offline.values()) == {200}, offline)
        ctx3.set_offline(False)
        ctx3.close()
        browser.close()

    section("⑥ 服务端下发 = 磁盘")
    check("下发的 band-core.js 哈希 = 磁盘",
          sha256_http("/band-core.js") == sha256_file(ROOT / "src" / "main" / "resources" / "static" / "band-core.js"),
          sha256_http("/band-core.js")[:16])
    for name in (ACCENT, WEAK):
        check(f"下发的 {name} 哈希 = 磁盘",
              sha256_http("/sounds/" + name) == sha256_file(SOUNDS / name),
              sha256_http("/sounds/" + name)[:16])

    print("\n合计 %d 项，失败 %d 项" % (total[0], len(failures)))
    if failures:
        print("失败项：")
        for f in failures:
            print("  - " + f)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
