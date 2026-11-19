# Lucide 图标子集（自托管）

本目录是**第三方前端资源**，按 `DECISIONS.md` **D7** 自托管、随仓库一起走，**不引用外部 CDN**。
用它的理由与边界见 `DECISIONS.md` **D26**；供应链规矩见 `SECURITY.md` **H1 / H3**；
图表在界面上怎么用见 `docs/14-前端资源与样式规范.md` §14.6.4「DOM 契约」。

> ⚠️ **仓库里只允许存在这一份。** 一个库一个目录、**一个库只存一份**（`AGENTS.md` 前端资源第 1 条）。
>
> ⚠️ **素材一律不许改造**：`.svg` 文件是**上游原样**，改一个字节就废掉下面那张哈希表。
> 尺寸、颜色、粗细一律由 `band.css`（`stroke: currentColor` + 父元素颜色）控制，**不在素材里做**。

---

## 一、版本与来源

| 项 | 值 |
|---|---|
| 库 | **Lucide**（`lucide-static`，[Feather Icons](https://feathericons.com) 的社区分支） |
| 版本 | **1.49.0** |
| 官网 / 仓库 | https://lucide.dev ｜ https://github.com/lucide-icons/lucide |
| 许可 | **ISC**；其中 **2 个**（`music` / `smartphone`）派生自 Feather，**MIT**（Copyright (c) 2013-present Cole Bemis）——两段全文都在本目录 `LICENSE` |
| 取的路径 | `icons/<key>.svg` |
| 入库日期 | 2026-09-30 |

## 二、校验（三源，2026-09-30 实测）

| 来源 | 地址 | 结果 |
|---|---|---|
| **npm 官方 tarball**（主） | `https://registry.npmjs.org/lucide-static/-/lucide-static-1.49.0.tgz` | ✅ 包的 **sha512 = `sha512-gSjtl7bR2v26oDInh2Wua1PrF2BRRXl9inhnJf7MgL/uykBpiqgKpNqS6QfPnNUTfp2VvM6GK3wj8bI5pFqV5w==`**，与 npm 公布的 `integrity` 逐字符一致；包内 20 个 `.svg` 与本目录**逐字节一致** |
| unpkg | `https://unpkg.com/lucide-static@1.49.0/icons/<key>.svg` | ✅ 与 jsdelivr 逐字节一致 |
| jsdelivr | `https://cdn.jsdelivr.net/npm/lucide-static@1.49.0/icons/<key>.svg` | ✅ 同上 |

⚠️ **`registry.npmmirror.com/lucide-static/1.49.0/files/…` 返回 404**——这个库在该镜像上**没有** Bootstrap 那种
`files/` 文件接口。所以第三次校验改用 **npm 官方 tarball**（那本来就是源头，比镜像更强）。
**下次升级照这条做**，别在 npmmirror 的文件接口上耗时间。

## 三、文件清单（逐文件 SHA256）

| key | file | 字节 | SHA256 |
|---|---|---|---|
| `bell` | `bell.svg` | 468 | 5227140d3559a4794233779b5008c75e2475db4f761aaa4fdbec14c914721f8a |
| `calendar-cog` | `calendar-cog.svg` | 777 | 11b86595e9e006c919881a2f59f1a44f4331746ae2f450302536f040b62432f6 |
| `calendar-days` | `calendar-days.svg` | 567 | efdf0439170fc012e02925b369cbe63ff3898651591e4a84eccec46f400c6d8d |
| `calendar-minus` | `calendar-minus.svg` | 456 | febf6a359f6d25b334e31051089b0caf0c13600af8fbb0233c3f9d8689257129 |
| `chart-column` | `chart-column.svg` | 403 | 66198f9492d925bf92ce3f4c8275aef118560e04e1416a401d1ce16e8e553c4d |
| `circle-user-round` | `circle-user-round.svg` | 418 | a49787554b78a4d1dd32359f740481ebdbee03e8c7737cf1401a8fce32926da0 |
| `clipboard-check` | `clipboard-check.svg` | 469 | 68eaddbd27b258feb7bdd22b4404152a194046bb9e83e13c1603a6929a29cfdd |
| `images` | `images.svg` | 522 | 22606eb41e5abee2d747925b584ae99f12f309750721f15fd832fd8c66337fdf |
| `inbox` | `inbox.svg` | 464 | 1ea0261415ddf6fc6cb468ced520a8a75ca2f99e2581f388cf513b8c3f6856af |
| `laptop` | `laptop.svg` | 482 | ba31e3905885a10332b0b54b7491415672f571b351187e5f815d1f4383369125 |
| `mail` | `mail.svg` | 391 | a96db905ade0fbf9bb58a07118f2c24051c7d4714f847ff74813eef45a1fa562 |
| `megaphone` | `megaphone.svg` | 511 | b5a11e8998b92dd160e839ffd45c8d9ab4b9611d1a433345d9d343dbc2997e3f |
| `messages-square` | `messages-square.svg` | 543 | eaad0af0779c6ef7816f2468017b3a1aa58338486dc85aa1ba6b276a6b1454b0 |
| `mic` | `mic.svg` | 400 | b1be29238f563ba7e3623ad8e0e0bc51285f17c8bd5d4c37aa5c6b6263e2dbf8 |
| `music` | `music.svg` | 384 | 4342c822e4599beda6eed52d63b754a0511e447b5c19a13ad636b2c3c7594f94 |
| `newspaper` | `newspaper.svg` | 496 | 594c08f861753af41a6e7239bed08620b7f2419d6d5946b33290883ef2739ccf |
| `scroll-text` | `scroll-text.svg` | 501 | c94cd7e157e10a8b1e5d806dc63625c1a91282235de0dc7830508487389fdfe6 |
| `smartphone` | `smartphone.svg` | 375 | b260bfa6b7c1e53c8fb9c1ec253c5cc6abdf4bb75ce61d8e3eb55d9eb227d5b1 |
| `ticket` | `ticket.svg` | 473 | 30a7a9c122706741387f4254012de1ce01830736d85662a74303785d24164d4f |
| `user-cog` | `user-cog.svg` | 707 | be449e2d70f97275c0143ceb2e3d72862c9f1fa39a928dd097b5dfc56ac9af1a |
| `users` | `users.svg` | 459 | 8a88a935b10bc68abff339291915223e0f79e3b581e466e8fec92631482d6081 |

`LICENSE` 的 SHA256：`b495047bd93a9b06913511076f504daba17d5bbeb3e0650f3bb53a4220329c57`

## 四、怎么用（实现方看这里）

- 图标自带统一规格：`viewBox="0 0 24 24"` `fill="none"` `stroke="currentColor"` `stroke-width="2"`
  `stroke-linecap="round"` `stroke-linejoin="round"`（见 `manifest.json` 的 `svg_attrs`）。
  ⚠️ 这与项目**原有**那个搜索放大镜（`concerts.html` 的 `.sc-sicon` + `band.css` 里那条）**一字不差**——
  D26 选这个库就是因为这个。
- **`menu` 第 3 位存 key**（如 `calendar-days`），`menuMarkup` 查表后包成 `<svg>`。
  ⚠️ **别把 SVG 源码抄进 `menu`**（那一列要能一眼读完）。
- **图标数据放在 `band-shell.js`**（导航骨架模块，23 个页面都引入它；`ICON_SVG` 由 `design-samples/_gen_icons_js.py` 生成，与菜单同址）。
  ⚠️ **不要为此给 21 个页面各加一个 `<script>` 标签。**
- 颜色由 `stroke: currentColor` 继承：收起态取 `--shellmid`、hover / 当前项取 `--silk`（与原来那个汉字同一条色路）。
  ⚠️ **细描边比实心字更吃对比度**——换完要在深木底上**重测 ≥ 4.5:1**（原来汉字版实测约 6.5:1）。
- ⚠️ **别整包搬**：整包约 1600 个图标。要新的就照上面那条命令取单个、补哈希、把本表与 `manifest.json` 一起更新。
- ⚠️ **换图标要同步三处**：本目录的文件 + `manifest.json` + `band-shell.js` 的 `menu`。

## 五、这批图标各用在哪

| key | 用在哪 | 备注 |
|---|---|---|
| `bell` | **右上角铃铛**——「反馈 + 更新」合并成的那个入口 | #227 起点开只剩「更新公告」一项，未读时带朱砂红点 |
| `calendar-cog` | 菜单 · 活动管理 | ⚠️ 日历三兄弟之一 |
| `calendar-days` | 菜单 · 活动安排 |  |
| `calendar-minus` | 菜单 · 我的请假 | ⚠️ 日历三兄弟之一 |
| `chart-column` | 菜单 · 考勤统计 |  |
| `circle-user-round` | **样张用**：我的资料（离线项） | ⚠️ 同上，`profile.html` 不占菜单 |
| `clipboard-check` | 菜单 · 请假审批 | 与「我的请假」是配对概念，但**形状必须不同** |
| `images` | 菜单 · 专场策展 | ⚠️ 样张里它曾和「活动安排」撞过（都用了 `◈`） |
| `inbox` | 菜单 · 反馈管理 |  |
| `laptop` | **样张用**：登录设备列表里的"电脑" | ⚠️ 真站 `sessions.html` 渲染的是服务端给的设备文字，当前**不需要** |
| `mail` | **右上角信封**——声部长留言 | ⚠️ **条件渲染**：只有历任/现任 `SECTION_LEADER` 看得到 |
| `megaphone` | 菜单 · 更新公告（管理端发布） | ⚠️ **不能与右上角铃铛共用**——`bell` 已给合并入口 |
| `messages-square` | 菜单 · 问题讨论（日常「我的」） | 2026-10-26 新增（#227）；不得复用 `inbox` / `megaphone` |
| `mic` | 菜单 · 练习录音 |  |
| `music` | 菜单 · 曲目库 | ⚠️ 派生自 Feather（见 LICENSE 第二节） |
| `newspaper` | **样张用**：更新日志（离线项） | ⚠️ 真站 `updates.html` 不占菜单，当前**不需要**这个图标 |
| `scroll-text` | 菜单 · 操作日志 | ⚠️ 样张里它曾和「更新公告」撞过（都用了 `☰`） |
| `smartphone` | **样张用**：登录设备列表里的"手机" | ⚠️ 同上；派生自 Feather |
| `ticket` | 菜单 · 演出 |  |
| `user-cog` | 菜单 · 团员管理 |  |
| `users` | 菜单 · 名册 | ⚠️ 与 `user-cog` 同为"人形"，收起态靠内部记号区分 |

⚠️ 标了「**样张用**」的 4 个（`newspaper` / `circle-user-round` / `laptop` / `smartphone`）
**真站当前用不到**（那三个页面都不占菜单），是给 `design-samples/mv5-mock.html` 用的。
一并入库是为了**下次不用重查**——它们与其余 16 个同一版本、同一次校验。

⚠️ **三处"基础形状相同"，收起态（92px 纯图标列、没有文字兜底）最需要人眼过一遍**：
`calendar-days` / `calendar-minus` / `calendar-cog` 三个都是日历外形；
`users` / `user-cog` 两个都是人形。**分不分得开由用户看样张判**。
