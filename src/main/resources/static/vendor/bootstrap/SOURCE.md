# Bootstrap（自托管）

本目录是**第三方前端资源**，按 `DECISIONS.md` D7 自托管、随仓库一起走，**不引用外部 CDN**。
理由见 D7（校园网下取不到就是白板；BootCDN 有投毒前科且专挑移动端下发）。

> ⚠️ **仓库里只允许存在这一份 Bootstrap。** 所有页面统一从本目录引入
> （`/vendor/bootstrap/bootstrap.min.css` 与 `/vendor/bootstrap/bootstrap.bundle.min.js`），
> 各页面在 `<head>` 里显式写，**不要用 JS 动态注入**。
> 2026-09-22 踩过一次：另一份 5.3.3 被平铺放在 `vendor/` 下且被页面引用，
> 与这份版本不同、还绕过了哈希校验。规矩详见 `docs/14-前端资源与样式规范.md`。

## 版本

**Bootstrap 5.3.8**（5.3 线的最新稳定版，2026-09-21 下载时确认）。

| 文件 | 用途 | 大小 | SHA256 |
|---|---|---|---|
| `bootstrap.min.css` | 样式 | 232,111 B | `d85327d99c7a3ee1f9b5d0500d1370acea3ad2db39c163c2f51f232baedbdede` |
| `bootstrap.bundle.min.js` | 组件（**bundle** 版自带 Popper） | 80,496 B | `e4fd49181388c48ec5040bd3fe66f57c29c8e67fcd8502b3354b96ec7ab47cc7` |
| `LICENSE` | Bootstrap 的 MIT 许可证 | 1,093 B | — |

## 来源与校验

下载日期：2026-09-21。三个独立来源各取一份，**SHA256 全部一致**才入库：

- `https://registry.npmmirror.com/bootstrap/5.3.8/files/dist/css/bootstrap.min.css`（国内镜像）
- `https://cdn.jsdelivr.net/npm/bootstrap@5.3.8/dist/css/bootstrap.min.css`
- `https://unpkg.com/bootstrap@5.3.8/dist/css/bootstrap.min.css`

同法取 `.js` 的两份，哈希同样一致。文件头自带版本标识（`Bootstrap v5.3.8 … Licensed under MIT`）。
**多源比对是刻意的**：单一来源若被投毒，哈希不会自己暴露问题；两源不一致时不许入库，先查明原因。

## 期 1 怎么引用

`static/` 由 Spring Boot 默认挂在根路径，所以页面里直接写：

```html
<link rel="stylesheet" href="/vendor/bootstrap/bootstrap.min.css">
<script src="/vendor/bootstrap/bootstrap.bundle.min.js"></script>
```

## 升级怎么做

手动：改上面的版本号 → 重新多源下载 → **哈希三源一致** → 替换文件 → 更新本文件的表格与哈希。
没有构建链就不会自动跟版本，这是 D7 已接受的代价。
