# PWA 普通图标圆角 #261 · 生产导出

- 输入（#252 普通图，未改动）：`src/main/resources/static/icons/pwa-252/`
- 生成脚本：`tools/make_rounded_icons_261.py`（可重复执行，同一 Pillow / 输入下字节一致）
- 圆角半径 = 边长 **24%**：192 → 46.08px，512 → 122.88px；8× 超采样掩膜后 LANCZOS 降采样，保留小数精度与平滑 alpha 边缘。
- 仅改外轮廓 alpha：四角 alpha=0、内部 alpha=255，alpha=255 区域 RGB 与 #252 输入逐像素一致；不重绘 / 不缩放主体、不加底板或阴影。
- maskable（`pwa-252/icon-maskable-512.png`）、Apple touch、favicon 与原生 APK 均未改动，旧资源保留兼容。

| 输入 | 尺寸 | SHA256 |
|---|---|---|
| `pwa-252/icon-192.png` | 192×192 | `a06acc9852ff2663c60c68d7ba5a6be3bb56e8e16e0524f9ae623d33fe5728db` |
| `pwa-252/icon-512.png` | 512×512 | `5af66502337fff749daa0466f9776b7e8dd248a4e72ea143ce8291ec2c2059a5` |

| 输出 | 尺寸 | SHA256 |
|---|---|---|
| `icon-192.png` | 192×192 | `9f4741ff9adec64b56d161ed06f1683a60f1e9815b79d29e0001a2e1ebdbd144` |
| `icon-512.png` | 512×512 | `2779682270eb7f0ab4a764caa81bb44786ec5dc2e0efdba8f7e7aba6f96f7194` |

生成命令：`python tools/make_rounded_icons_261.py`
验收命令：`python tools/check_pwa_rounded_261.py`
