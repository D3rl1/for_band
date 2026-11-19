#!/usr/bin/env python
# -*- coding: utf-8 -*-
r"""合成节拍器兼容模式的两条点击音（#258：8ms 峰值保持包络）。

由来：兼容模式只能靠 WAV 本身发声，高精度模式的增益包络在这里没有地方写。
上一版脚本是「把旧 WAV 整体按峰值放大」，包络仍是旧的（峰后立刻衰减）；#258 定稿
改用**峰值保持 8ms** 的包络（2026-11-12 用户点验选定「上一档」），所以这里改成
**从参数直接合成**，不再读写旧 WAV。

正式参数（`docs/14` §14.6.6「响度提升试听与定稿」）：
  重拍 1600Hz / 峰值 0.90，弱拍 1000Hz / 峰值 0.45；44.1kHz / 单声道 / 16-bit / 80ms。
  增益：t=0 为 0.0001 → 指数升到 t+0.004s 的峰值 → **保持到 t+0.012s** →
  指数降到 t+0.060s 的 0.0001 → t+0.080s 停声。不归一化、不硬裁。

用法：`python tools/gen_metro_wavs.py`（写完打印峰值 / RMS / SHA256，并核对点验基准哈希）。
"""
import hashlib
import math
import os
import sys
import wave

try:
    sys.stdout.reconfigure(encoding="utf-8")
except Exception:  # noqa: BLE001  老解释器 / 已重定向
    pass

HERE = os.path.dirname(os.path.abspath(__file__))
SOUNDS = os.path.join(HERE, "..", "src", "main", "resources", "static", "sounds")

SR = 44100
DURATION = 0.080
ATTACK = 0.004        # 指数起音到峰值
HOLD_END = 0.012      # 峰值保持段的终点（即 8ms 保持）
DECAY_END = 0.060     # 指数衰减到 0.0001
FLOOR = 0.0001

# 输出名 → (频率, 峰值)。带 -258 避免旧 Service Worker 缓存里的点击音被误用。
SPECS = {
    "metro-accent-258.wav": (1600.0, 0.90),
    "metro-weak-258.wav": (1000.0, 0.45),
}

# 点验基准：`design-samples/metro-louder-study/proposed-accent.wav` / `proposed-weak.wav`
EXPECTED_SHA256 = {
    "metro-accent-258.wav": "5ae20003dd967d70fcdcaf3e596fbc6be679376277305709d81528f79730a474",
    "metro-weak-258.wav": "5c6e228805ae4743efd688865fb5a9a2356a04b483bdbfe7f07b05760ab84bb0",
}


def gain_at(t, peak):
    """#258 的增益包络（与 Web Audio 的四段自动化一一对应）。"""
    if t < ATTACK:
        return FLOOR * (peak / FLOOR) ** (t / ATTACK)
    if t < HOLD_END:
        return peak
    if t < DECAY_END:
        return peak * (FLOOR / peak) ** ((t - HOLD_END) / (DECAY_END - HOLD_END))
    return FLOOR


def synth(freq, peak):
    n = round(DURATION * SR)
    return [gain_at(i / SR, peak) * math.sin(2 * math.pi * freq * (i / SR)) for i in range(n)]


def write_wav(path, values):
    frames = b"".join(round(v * 32767).to_bytes(2, "little", signed=True) for v in values)
    with wave.open(path, "wb") as w:
        w.setparams((1, 2, SR, len(values), "NONE", "not compressed"))
        w.writeframes(frames)


def rms(values):
    return math.sqrt(sum(v * v for v in values) / len(values))


def main():
    os.makedirs(SOUNDS, exist_ok=True)
    bad = []
    for name, (freq, peak) in SPECS.items():
        values = synth(freq, peak)
        path = os.path.join(SOUNDS, name)
        write_wav(path, values)
        with open(path, "rb") as f:
            sha = hashlib.sha256(f.read()).hexdigest()
        got_peak = max(abs(v) for v in values)
        print("%s: %.0fHz peak=%.4f rms80=%.5f sha256=%s"
              % (name, freq, got_peak, rms(values), sha))
        want = EXPECTED_SHA256.get(name)
        if want and sha != want:
            bad.append("%s 与点验基准哈希不一致（want %s）" % (name, want))
    if bad:
        raise SystemExit("✗ " + "；".join(bad))
    print("✓ 两条音源与点验基准逐字节一致（%s / %s）" % (SR, DURATION * 1000) + "ms")


if __name__ == "__main__":
    main()
