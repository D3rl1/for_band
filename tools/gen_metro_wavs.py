#!/usr/bin/env python
# -*- coding: utf-8 -*-
r"""把兼容模式点击音按峰值缩放到 0.90 / 0.45（不归一化）。

兼容模式响度只能靠 WAV 本身；这里保留原波形/采样率/时长，只整体放大。
"""
import os
import wave

TARGET = {"metro-accent.wav": 0.90, "metro-weak.wav": 0.45}
SOUNDS = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                      "..", "src", "main", "resources", "static", "sounds")


def read_samples(path):
    with wave.open(path, "rb") as w:
        params = w.getparams()
        raw = w.readframes(w.getnframes())
    assert params.sampwidth == 2 and params.nchannels == 1, params
    vals = [int.from_bytes(raw[i:i + 2], "little", signed=True) / 32768.0
            for i in range(0, len(raw), 2)]
    return params, vals


def write_samples(path, params, vals):
    raw = bytearray()
    for v in vals:
        s = max(-1.0, min(1.0, v))
        raw += int(round(s * 32767)).to_bytes(2, "little", signed=True)
    with wave.open(path, "wb") as w:
        w.setparams(params)
        w.writeframes(bytes(raw))


def main():
    for name, target in TARGET.items():
        path = os.path.join(SOUNDS, name)
        params, vals = read_samples(path)
        peak = max(abs(v) for v in vals)
        k = target / peak
        write_samples(path, params, [v * k for v in vals])
        _, out = read_samples(path)
        got = max(abs(v) for v in out)
        print("%s: %.3fs, sr=%d, peak %.4f -> %.4f" %
              (name, len(vals) / params.framerate, params.framerate, peak, got))


if __name__ == "__main__":
    main()
