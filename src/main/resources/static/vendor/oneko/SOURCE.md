# oneko（页面萌宠，自托管）

本目录是**第三方前端资源**，按 `DECISIONS.md` D7 自托管、随仓库一起走，**不引用外部 CDN**。
用途与验收见 `docs/14-前端资源与样式规范.md` §14.6.4「页面萌宠」；
用不用本团专属形象替换是 `docs/00` §0.6 T15，换素材要重新走三源入库。

> ⚠️ **仓库里只允许存在这一份 oneko。** `design-samples/mascot-vendor/` 里那两份是
> 原型期的对照副本，**不许**直接拷来当库存，也不许被页面引用。
> **不要修改上游源码**：空闲睡觉/挠墙是 §14.6.6 第二条例外明确保留的行为，
> 且改了源码，「本地文件 = 上游哈希」的校验就废了。

## 版本

**adryd325/oneko.js**（单文件 + 单素材，无版本号发布；2026-09-23 下载时取 main 分支最新）。

| 文件 | 用途 | 大小 | SHA256 |
|---|---|---|---|
| `oneko.js` | 全部逻辑（自初始化 IIFE，含 `prefers-reduced-motion` 守卫） | 6,929 B | `e5ef6b4e522da7c6a1f74dda1e7a6396675aa3c997ec4cce5e76f9326be34d81` |
| `oneko.gif` | 精灵图（32px 网格） | 3,316 B | `f4c5688eb8ee6f22b08ab8df361222307dc6fe2d5bdcfa12582cea694707e66d` |
| `LICENSE` | 上游 MIT 许可证（Copyright © 2022 adryd） | 1,055 B | — |

## 来源与校验

下载日期：2026-09-23。三个独立来源各取一份，**SHA256 全部一致**才入库（与 docs/14
§14.6.4 里记录的两个哈希核对一致）：

- 原型期副本 `design-samples/mascot-vendor/`（仅作比对，不入库）
- `https://raw.githubusercontent.com/adryd325/oneko.js/main/oneko.js`（`oneko.gif` 同路径）
- `https://cdn.jsdelivr.net/gh/adryd325/oneko.js@main/`（raw 被网络重置时的兜底通道）

## 怎么引用

由 `app.js` 的 `loadCat()` **动态注入**（这是规格明确豁免的特例：手机端要在发请求之前
就拦下，"不加载"而不是"加载了再藏"）：

```js
s.src = '/vendor/oneko/oneko.js';
s.dataset.cat = '/vendor/oneko/oneko.gif';   // 素材路径走 data-cat
```

桌面 + 已登录 + 欢迎过渡播完之后才会注入；登录页（没有 `renderNav`）不会出现。

## 换素材 / 升级怎么做

重新三源下载 → **哈希三源一致** → 替换文件 → 更新本文件的表格与哈希；
并按 docs/14 §14.6.4 检查可动性前提（如"眼睛能定位"），走 `docs/00` T15 单独议。
